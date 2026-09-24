package com.metis.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.AttackDie;
import com.metis.engine.core.UnitDefinition;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.CallableStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Repository
public class DefinitionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DefinitionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<DefinitionVersion> findVersions(Long gameId) {
        return jdbc.query("SELECT * FROM game_engine.fn_definition_version_list(?)", (rs, rowNum) ->
                new DefinitionVersion(
                        rs.getLong("game_version_id"), rs.getLong("game_id"),
                        rs.getString("version_code"), rs.getString("version_name"),
                        rs.getString("lifecycle_status"), rs.getObject("release_date", LocalDate.class),
                        rs.getString("source_revision"), rs.getString("source_name"),
                        rs.getString("source_url"), rs.getString("published_at"),
                        rs.getString("created_at"), rs.getString("updated_at"),
                        rs.getBoolean("is_valid"), rs.getString("validation_errors")
                ), gameId);
    }

    public Optional<DefinitionVersion> findVersion(long definitionId) {
        return findVersions(null).stream()
                .filter(version -> version.definitionId() == definitionId)
                .findFirst();
    }

    public Optional<com.metis.engine.core.GameDefinition> findEngineDefinition(long definitionId) {
        var turnStructure = findTurnStructure(definitionId);
        return jdbc.query("SELECT * FROM game_engine.fn_engine_definition_get(?)", rs -> rs.next()
                ? Optional.of(new com.metis.engine.core.GameDefinition(rs.getLong("game_id"), rs.getString("game_code"),
                        rs.getString("game_name"), rs.getLong("definition_version_id"),
                        rs.getString("definition_version"), rs.getInt("minimum_players"),
                        rs.getInt("maximum_players"), rs.getInt("minimum_cpu"),
                        rs.getInt("maximum_cpu"), parseFactionCodes(rs.getString("faction_codes")),
                        parseUnitDefinitions(rs.getString("unit_definitions")), turnStructure))
                : Optional.empty(), definitionId);
    }

    public com.metis.engine.core.TurnStructure findTurnStructure(long definitionId) {
        String json = jdbc.queryForObject("SELECT game_engine.fn_definition_turn_structure_get(?)::text", String.class, definitionId);
        try { return json == null ? null : objectMapper.readValue(json, com.metis.engine.core.TurnStructure.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Invalid turn structure", error); }
    }

    public void updateTurnStructure(long definitionId, com.metis.engine.core.TurnStructure structure) {
        try { jdbc.update("CALL game_engine.sp_definition_turn_structure_update(?,?)", definitionId, objectMapper.writeValueAsString(structure)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("Invalid turn structure", error); }
    }

    public void captureSessionDefinition(long sessionId, com.metis.engine.core.GameDefinition definition) {
        DefinitionSnapshot snapshot = DefinitionSnapshot.capture(objectMapper, definition);
        jdbc.update("CALL game_instance.sp_session_definition_snapshot_create(?,?,?,?,?)", sessionId,
                snapshot.schemaVersion(), snapshot.rulesVersion(), snapshot.json(), snapshot.checksum());
    }

    public Optional<com.metis.engine.core.GameDefinition> findSessionDefinition(long sessionId, long expectedRevision) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_definition_snapshot_get(?)", rs -> rs.next()
                ? Optional.of(new DefinitionSnapshot(rs.getInt("snapshot_schema_version"), rs.getString("rules_version"),
                    rs.getString("definition_json"), rs.getString("checksum")).resolve(objectMapper, expectedRevision))
                : Optional.empty(), sessionId);
    }

    public List<Faction> findFactions(long definitionId) {
        return jdbc.query("SELECT faction_id, faction_name FROM game_engine.faction "
                        + "WHERE game_version_id = ? ORDER BY faction_id",
                (rs, rowNum) -> new Faction(rs.getLong("faction_id"), rs.getString("faction_name")),
                definitionId);
    }

    public JsonNode findProfileMapping(long definitionId) {
        String mapping = jdbc.queryForObject("SELECT game_engine.fn_definition_profile_mapping_get(?)::text",
                String.class, definitionId);
        try {
            return objectMapper.readTree(mapping == null ? "{}" : mapping);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Definition profile mapping is invalid", exception);
        }
    }

    public void updateProfileMapping(long definitionId, JsonNode mapping) {
        if (mapping == null || !mapping.isObject()) {
            throw new IllegalArgumentException("Profile mapping must be a JSON object");
        }
        jdbc.update("CALL game_engine.sp_definition_profile_mapping_update(?,?)", definitionId, mapping.toString());
    }

    private List<String> parseFactionCodes(String json) {
        try {
            return StreamSupport.stream(objectMapper.readTree(json).spliterator(), false)
                    .map(JsonNode::asText)
                    .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Engine definition faction data is invalid", exception);
        }
    }

    private List<UnitDefinition> parseUnitDefinitions(String json) {
        try {
            return StreamSupport.stream(objectMapper.readTree(json).spliterator(), false)
                    .map(unit -> new UnitDefinition(unit.get("code").asText(),
                            unit.get("factionCode").asText(),
                            unit.get("maximumMovementDistance").asDouble(),
                            unit.get("maximumAttackRange").asDouble(),
                            unit.get("attackDamage").asInt(),
                             unit.get("maximumHullPoints").asInt(),
                             unit.get("attackDice").asInt(),
                             unit.get("maximumShieldPoints").asInt(),
                             unit.get("defenseTokenCount").asInt(),
                             unit.hasNonNull("displayName") ? unit.get("displayName").asText() : unit.get("code").asText(),
                             unit.hasNonNull("classification") ? unit.get("classification").asText() : "Unit",
                             parseManeuverProfiles(unit.get("maneuverProfiles")),
                             unit.hasNonNull("baseRadius") ? unit.get("baseRadius").asDouble() : defaultBaseRadius(unit),
                             parseAttackArcs(unit.get("batteryArcs")),
                             parseAttackDice(unit.get("antiSquadronDice")),
                             parseIntegerMap(unit.get("shieldZones")),
                             parseStrings(unit.get("defenseTokenTypes")),
                             parseIntegerMap(unit.get("defenseTokenCounts")),
                             unit.path("commandValue").asInt(),
                             unit.path("engineeringValue").asInt(),
                             unit.path("squadronValue").asInt()))
                     .toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Engine definition unit data is invalid", exception);
        }
    }

    private Map<Integer, com.metis.engine.core.ManeuverProfile> parseManeuverProfiles(JsonNode json) {
        if (json == null || !json.isObject()) {
            return Map.of();
        }
        Map<Integer, com.metis.engine.core.ManeuverProfile> profiles = new LinkedHashMap<>();
        json.fields().forEachRemaining(entry -> {
            int speed = Integer.parseInt(entry.getKey());
            List<Integer> yaw = StreamSupport.stream(entry.getValue().spliterator(), false)
                    .map(JsonNode::asInt).toList();
            profiles.put(speed, new com.metis.engine.core.ManeuverProfile(speed, yaw));
        });
        return profiles;
    }

    private Map<String, List<AttackDie>> parseAttackArcs(JsonNode json) {
        if (json == null || !json.isObject()) return Map.of();
        Map<String, List<AttackDie>> arcs = new LinkedHashMap<>();
        json.fields().forEachRemaining(entry -> arcs.put(entry.getKey(), parseAttackDice(entry.getValue())));
        return arcs;
    }

    private List<AttackDie> parseAttackDice(JsonNode json) {
        if (json == null || !json.isArray()) return List.of();
        return StreamSupport.stream(json.spliterator(), false)
                .map(die -> new AttackDie(die.path("color").asText("generic"), die.path("count").asInt()))
                .toList();
    }

    private Map<String, Integer> parseIntegerMap(JsonNode json) {
        if (json == null || !json.isObject()) return Map.of();
        Map<String, Integer> values = new LinkedHashMap<>();
        json.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asInt()));
        return values;
    }

    private List<String> parseStrings(JsonNode json) {
        if (json == null || !json.isArray()) return List.of();
        return StreamSupport.stream(json.spliterator(), false).map(JsonNode::asText).toList();
    }

    private double defaultBaseRadius(JsonNode unit) {
        return "squadron".equalsIgnoreCase(unit.path("classification").asText()) ? 25 : 50;
    }

    public ValidationResult validate(long definitionId) {
        return jdbc.query("SELECT * FROM game_engine.fn_definition_version_validate(?)", rs -> rs.next()
                ? new ValidationResult(rs.getBoolean("is_valid"), rs.getString("errors"))
                : new ValidationResult(false, "[]"), definitionId);
    }

    public List<DefinitionAudit> findAudit(long definitionId) {
        return jdbc.query("SELECT * FROM game_engine.fn_definition_audit(?)", (rs, rowNum) ->
                new DefinitionAudit(rs.getLong("audit_id"), rs.getString("action_code"),
                        rs.getString("entity_type"), rs.getObject("entity_id", Long.class),
                        rs.getString("details_json"), rs.getString("occurred_at")), definitionId);
    }

    public long createVersion(long gameId, String code, String name, String sourceRevision) {
        return jdbc.execute((CallableStatementCreator) connection -> {
            CallableStatement statement = connection.prepareCall(
                    "{call game_engine.sp_definition_version_create(?,?,?,?,?)}");
            statement.setLong(1, gameId);
            statement.setString(2, code);
            statement.setString(3, name);
            setNullableString(statement, 4, sourceRevision);
            statement.registerOutParameter(5, Types.BIGINT);
            return statement;
        }, (CallableStatementCallback<Long>) statement -> {
            statement.execute();
            return statement.getLong(5);
        });
    }

    public void updateVersion(long definitionId, String code, String name, String sourceRevision) {
        execute("{call game_engine.sp_definition_version_update(?,?,?,?)}", statement -> {
            statement.setLong(1, definitionId);
            statement.setString(2, code);
            statement.setString(3, name);
            setNullableString(statement, 4, sourceRevision);
        });
    }

    public void publishVersion(long definitionId) {
        execute("{call game_engine.sp_definition_version_publish(?)}",
                statement -> statement.setLong(1, definitionId));
    }

    public void retireVersion(long definitionId) {
        execute("{call game_engine.sp_definition_version_retire(?)}",
                statement -> statement.setLong(1, definitionId));
    }

    public void updateObject(long definitionId, long objectId, String name, String description,
                             String displayText, String data, String sourceReference) {
        jdbc.update("CALL game_engine.sp_definition_object_update(?,?,?,?,?,?,?)", definitionId, objectId,
                name, description, displayText, data, sourceReference);
    }

    public void updateGame(long definitionId, String name, String description,
                           int minPlayers, int maxPlayers, int minCpu, int maxCpu) {
        jdbc.update("CALL game_engine.sp_definition_game_update(?,?,?,?,?,?,?)", definitionId, name,
                description, minPlayers, maxPlayers, minCpu, maxCpu);
    }

    public void updateFaction(long definitionId, long factionId, String name, String description) {
        jdbc.update("CALL game_engine.sp_definition_faction_update(?,?,?,?)", definitionId, factionId,
                name, description);
    }

    public void updateRule(long definitionId, long ruleId, String name, String description, String characteristics) {
        jdbc.update("CALL game_engine.sp_definition_rule_update(?,?,?,?,?)", definitionId, ruleId,
                name, description, characteristics);
    }

    public void updateMode(long definitionId, long modeId, String name, String description, String configuration) {
        jdbc.update("CALL game_engine.sp_definition_mode_update(?,?,?,?,?)", definitionId, modeId,
                name, description, configuration);
    }

    public void updatePhase(long definitionId, long phaseId, String name, String description, String configuration) {
        jdbc.update("CALL game_engine.sp_definition_phase_update(?,?,?,?,?)", definitionId, phaseId,
                name, description, configuration);
    }

    public void updateAction(long definitionId, long actionId, String name, String description, String configuration) {
        jdbc.update("CALL game_engine.sp_definition_action_update(?,?,?,?,?)", definitionId, actionId,
                name, description, configuration);
    }

    public void updateScenario(long definitionId, long scenarioId, String name, String description, String configuration) {
        jdbc.update("CALL game_engine.sp_definition_scenario_update(?,?,?,?,?)", definitionId, scenarioId,
                name, description, configuration);
    }

    public void updateBoard(long definitionId, long boardId, String name, java.math.BigDecimal width,
                            java.math.BigDecimal height, String coordinateSystem, String configuration) {
        jdbc.update("CALL game_engine.sp_definition_board_update(?,?,?,?,?,?,?)", definitionId, boardId,
                name, width, height, coordinateSystem, configuration);
    }

    private void execute(String call, StatementConfigurer configurer) {
        jdbc.execute((CallableStatementCreator) connection -> {
            CallableStatement statement = connection.prepareCall(call);
            configurer.configure(statement);
            return statement;
        }, (CallableStatementCallback<Void>) statement -> {
            statement.execute();
            return null;
        });
    }

    private static void setNullableString(CallableStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    @FunctionalInterface
    private interface StatementConfigurer {
        void configure(CallableStatement statement) throws java.sql.SQLException;
    }

    public record DefinitionVersion(long definitionId, long gameId, String code, String name,
                                    String status, LocalDate releaseDate, String sourceRevision,
                                    String sourceName, String sourceUrl, String publishedAt,
                                    String createdAt, String updatedAt, boolean valid,
                                    String validationErrors) {}

    public record ValidationResult(boolean valid, String errors) {}

    public record Faction(long factionId, String factionName) {}

    public record DefinitionAudit(long auditId, String action, String entityType, Long entityId,
                                  String details, String occurredAt) {}

}
