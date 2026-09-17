package com.metis.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class BattleTemplateRepository {

    private static final TypeReference<List<TemplatePlayer>> PLAYERS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OPTIONS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BattleTemplateRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<BattleTemplate> findTemplates(Long gameId) {
        return jdbc.query("""
                SELECT template_id, game_id, template_key, template_name, description,
                       game_version_id, game_mode_id, scenario_id, fleet_format_id,
                       board_code, board_width, board_height, options_json, players_json,
                       template_session_id
                FROM game_instance.battle_template
                WHERE (CAST(? AS bigint) IS NULL OR game_id = ?)
                ORDER BY template_name, template_id
                """, (rs, rowNum) -> mapTemplate(rs.getLong("template_id"), rs.getLong("game_id"),
                        rs.getString("template_key"), rs.getString("template_name"),
                        rs.getString("description"), rs.getObject("game_version_id", Long.class),
                        rs.getObject("game_mode_id", Long.class), rs.getObject("scenario_id", Long.class),
                        rs.getObject("fleet_format_id", Long.class), rs.getString("board_code"),
                        rs.getDouble("board_width"), rs.getDouble("board_height"),
                        rs.getString("options_json"), rs.getString("players_json"),
                        rs.getObject("template_session_id", Long.class)), gameId, gameId);
    }

    public Optional<BattleTemplate> findTemplate(long templateId) {
        return jdbc.query("""
                SELECT template_id, game_id, template_key, template_name, description,
                       game_version_id, game_mode_id, scenario_id, fleet_format_id,
                       board_code, board_width, board_height, options_json, players_json,
                       template_session_id
                FROM game_instance.battle_template
                WHERE template_id = ?
                """, rs -> rs.next()
                ? Optional.of(mapTemplate(rs.getLong("template_id"), rs.getLong("game_id"),
                        rs.getString("template_key"), rs.getString("template_name"),
                        rs.getString("description"), rs.getObject("game_version_id", Long.class),
                        rs.getObject("game_mode_id", Long.class), rs.getObject("scenario_id", Long.class),
                        rs.getObject("fleet_format_id", Long.class), rs.getString("board_code"),
                        rs.getDouble("board_width"), rs.getDouble("board_height"),
                        rs.getString("options_json"), rs.getString("players_json"),
                        rs.getObject("template_session_id", Long.class)))
                : Optional.empty(), templateId);
    }

    public Optional<CanonicalSetup> findCanonicalSetup(long gameVersionId) {
        return jdbc.query("""
                SELECT gm.game_mode_id, s.scenario_id, ff.fleet_format_id,
                       bd.board_definition_id, bd.board_code, bd.width, bd.height,
                       ff.points_limit, gm.mode_config_json
                FROM game_engine.game_mode gm
                JOIN game_engine.game_mode_scenario gms ON gms.game_mode_id = gm.game_mode_id
                JOIN game_engine.scenario_definition s ON s.scenario_id = gms.scenario_id
                JOIN game_engine.fleet_format ff ON ff.game_mode_id = gm.game_mode_id
                JOIN game_engine.scenario_board sb ON sb.scenario_id = s.scenario_id
                JOIN game_engine.board_definition bd ON bd.board_definition_id = sb.board_definition_id
                WHERE gm.game_version_id = ?
                  AND gm.game_mode_code = 'standard_battle'
                  AND s.game_version_id = ?
                  AND s.scenario_code = 'standard_battle'
                  AND ff.format_code = 'standard_fleet'
                  AND bd.game_version_id = ?
                  AND bd.board_code = 'standard_6x3'
                ORDER BY gms.is_default DESC, sb.board_role = 'primary' DESC
                LIMIT 1
                """, rs -> rs.next()
                ? Optional.of(new CanonicalSetup(rs.getLong("game_mode_id"), rs.getLong("scenario_id"),
                        rs.getLong("fleet_format_id"), rs.getLong("board_definition_id"),
                        rs.getString("board_code"), rs.getDouble("width"), rs.getDouble("height"),
                        rs.getInt("points_limit"), normalizedOptions(rs.getString("mode_config_json"))))
                : Optional.empty(), gameVersionId, gameVersionId, gameVersionId);
    }

    public void configureSession(long sessionId, CanonicalSetup setup) {
        jdbc.update("""
                UPDATE game_instance.game_session
                SET game_mode_id = ?, scenario_id = ?, fleet_format_id = ?, updated_at = now()
                WHERE session_id = ?
                """, setup.gameModeId(), setup.scenarioId(), setup.fleetFormatId(), sessionId);
    }

    public long upsertTemplate(TemplateSpec spec) {
        String options = writeJson(spec.options());
        String players = writeJson(spec.players());
        jdbc.update("""
                INSERT INTO game_instance.battle_template
                    (game_id, template_key, template_name, description, game_version_id,
                     game_mode_id, scenario_id, fleet_format_id, board_code, board_width,
                     board_height, options_json, players_json, template_session_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now())
                ON CONFLICT (template_key) DO UPDATE SET
                    game_id = EXCLUDED.game_id,
                    template_name = EXCLUDED.template_name,
                    description = EXCLUDED.description,
                    game_version_id = EXCLUDED.game_version_id,
                    game_mode_id = EXCLUDED.game_mode_id,
                    scenario_id = EXCLUDED.scenario_id,
                    fleet_format_id = EXCLUDED.fleet_format_id,
                    board_code = EXCLUDED.board_code,
                    board_width = EXCLUDED.board_width,
                    board_height = EXCLUDED.board_height,
                    options_json = EXCLUDED.options_json,
                    players_json = EXCLUDED.players_json,
                    template_session_id = EXCLUDED.template_session_id,
                    updated_at = now()
                """, spec.gameId(), spec.key(), spec.name(), spec.description(), spec.gameVersionId(),
                spec.gameModeId(), spec.scenarioId(), spec.fleetFormatId(), spec.boardCode(),
                spec.boardWidth(), spec.boardHeight(), options, players, spec.templateSessionId());
        return jdbc.queryForObject("SELECT template_id FROM game_instance.battle_template WHERE template_key = ?",
                Long.class, spec.key());
    }

    public void assignSessionTemplate(long sessionId, long templateId) {
        jdbc.update("UPDATE game_instance.game_session SET template_id = ?, updated_at = now() WHERE session_id = ?",
                templateId, sessionId);
    }

    public void initializeRunMetadata(long sessionId, long templateId, String aiVersion) {
        jdbc.update("""
                UPDATE game_instance.game_session s
                SET template_id = ?, ai_version = ?,
                    run_number = COALESCE((
                        SELECT MAX(previous.run_number) + 1
                        FROM game_instance.game_session previous
                        WHERE previous.template_id = ? AND previous.session_id <> s.session_id
                    ), 1),
                    updated_at = now()
                WHERE s.session_id = ?
                """, templateId, aiVersion, templateId, sessionId);
    }

    public void setSourceMetadata(long sessionId, String aiVersion) {
        jdbc.update("UPDATE game_instance.game_session SET ai_version = ?, run_number = 0, updated_at = now() WHERE session_id = ?",
                aiVersion, sessionId);
    }

    private BattleTemplate mapTemplate(long templateId, long gameId, String key, String name, String description,
                                       Long gameVersionId, Long gameModeId, Long scenarioId, Long fleetFormatId,
                                       String boardCode, double boardWidth, double boardHeight,
                                       String optionsJson, String playersJson, Long templateSessionId) {
        try {
            return new BattleTemplate(templateId, gameId, key, name, description, gameVersionId, gameModeId,
                    scenarioId, fleetFormatId, boardCode, boardWidth, boardHeight,
                    objectMapper.readValue(optionsJson, OPTIONS_TYPE),
                    objectMapper.readValue(playersJson, PLAYERS_TYPE), templateSessionId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored battle template JSON is invalid", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize battle template", exception);
        }
    }

    private Map<String, Object> normalizedOptions(String json) {
        try {
            Map<String, Object> options = new LinkedHashMap<>(objectMapper.readValue(json, OPTIONS_TYPE));
            Object movement = options.get("maximumMovementDistance");
            if (movement != null) options.put("distancePerSpeed", movement);
            Object rounds = options.get("roundLimit");
            if (rounds != null) options.put("maximumRounds", rounds);
            return options;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored game mode JSON is invalid", exception);
        }
    }

    public record TemplateSpec(long gameId, String key, String name, String description,
                               Long gameVersionId, Long gameModeId, Long scenarioId, Long fleetFormatId,
                               String boardCode, double boardWidth, double boardHeight,
                               Map<String, Object> options, List<TemplatePlayer> players,
                               Long templateSessionId) {}

    public record BattleTemplate(long templateId, long gameId, String key, String name, String description,
                                 Long gameVersionId, Long gameModeId, Long scenarioId, Long fleetFormatId,
                                 String boardCode, double boardWidth, double boardHeight,
                                 Map<String, Object> options, List<TemplatePlayer> players,
                                 Long templateSessionId) {}

    public record TemplatePlayer(int seatNumber, String displayName, String playerKind,
                                 Long factionId, String factionName, String fleetName,
                                 Integer pointsTotal) {}

    public record CanonicalSetup(long gameModeId, long scenarioId, long fleetFormatId,
                                 long boardDefinitionId, String boardCode, double boardWidth,
                                 double boardHeight, int pointsLimit, Map<String, Object> options) {}
}
