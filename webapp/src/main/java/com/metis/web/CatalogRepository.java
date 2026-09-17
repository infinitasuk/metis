package com.metis.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long publishedDefinitionId(long gameId) {
        Long definitionId = jdbc.queryForObject(
                "SELECT game_engine.fn_published_definition_id(?)", Long.class, gameId);
        if (definitionId == null) {
            throw new IllegalStateException("Game " + gameId + " has no published definition");
        }
        return definitionId;
    }

    public List<GameSummary> findGames() {
        return jdbc.query("""
                SELECT g.game_id, g.game_name, g.game_description,
                       g.minimum_number_of_players, g.maximum_number_of_players,
                       g.minimum_number_of_cpu, g.maximum_number_of_cpu,
                       count(DISTINCT f.faction_id) AS faction_count,
                       count(DISTINCT ut.unit_type_id) AS unit_type_count,
                       count(DISTINCT uv.unit_variant_id) AS variant_count,
                       count(DISTINCT go.game_object_id) AS object_count
                FROM game_engine.game g
                 LEFT JOIN game_engine.faction f ON f.game_id = g.game_id
                     AND f.game_version_id = game_engine.fn_published_definition_id(g.game_id)
                LEFT JOIN game_engine.unit_classification uc ON uc.faction_id = f.faction_id
                LEFT JOIN game_engine.unit_type ut ON ut.unit_classification_id = uc.unit_classification_id
                LEFT JOIN game_engine.unit_variant uv ON uv.unit_type_id = ut.unit_type_id
                 LEFT JOIN game_engine.game_object go ON go.game_id = g.game_id
                     AND go.game_version_id = game_engine.fn_published_definition_id(g.game_id)
                GROUP BY g.game_id
                ORDER BY g.game_name
                """, (rs, rowNum) -> new GameSummary(
                rs.getLong("game_id"),
                rs.getString("game_name"),
                rs.getString("game_description"),
                rs.getInt("minimum_number_of_players"),
                rs.getInt("maximum_number_of_players"),
                rs.getInt("minimum_number_of_cpu"),
                rs.getInt("maximum_number_of_cpu"),
                 rs.getLong("faction_count"),
                 rs.getLong("unit_type_count"),
                 rs.getLong("variant_count"),
                 rs.getLong("object_count")
        ));
    }

    public Optional<GameSummary> findGame(long gameId) {
        return findGame(gameId, publishedDefinitionId(gameId));
    }

    public Optional<GameSummary> findGame(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT g.game_id,
                       COALESCE(gv.definition_game_name, g.game_name) AS game_name,
                       COALESCE(gv.definition_game_description, g.game_description) AS game_description,
                       COALESCE(gv.definition_min_players, g.minimum_number_of_players) AS minimum_number_of_players,
                       COALESCE(gv.definition_max_players, g.maximum_number_of_players) AS maximum_number_of_players,
                       COALESCE(gv.definition_min_cpu, g.minimum_number_of_cpu) AS minimum_number_of_cpu,
                       COALESCE(gv.definition_max_cpu, g.maximum_number_of_cpu) AS maximum_number_of_cpu,
                       count(DISTINCT f.faction_id) AS faction_count,
                       count(DISTINCT ut.unit_type_id) AS unit_type_count,
                       count(DISTINCT uv.unit_variant_id) AS variant_count,
                       count(DISTINCT go.game_object_id) AS object_count
                FROM game_engine.game g
                 JOIN game_engine.game_version gv ON gv.game_version_id = ? AND gv.game_id = g.game_id
                 LEFT JOIN game_engine.faction f ON f.game_id = g.game_id
                     AND f.game_version_id = ?
                LEFT JOIN game_engine.unit_classification uc ON uc.faction_id = f.faction_id
                LEFT JOIN game_engine.unit_type ut ON ut.unit_classification_id = uc.unit_classification_id
                LEFT JOIN game_engine.unit_variant uv ON uv.unit_type_id = ut.unit_type_id
                 LEFT JOIN game_engine.game_object go ON go.game_id = g.game_id
                     AND go.game_version_id = ?
                 WHERE g.game_id = ?
                 GROUP BY g.game_id, gv.definition_game_name, gv.definition_game_description,
                          gv.definition_min_players, gv.definition_max_players,
                          gv.definition_min_cpu, gv.definition_max_cpu
                 """, rs -> rs.next()
                ? Optional.of(new GameSummary(
                        rs.getLong("game_id"),
                        rs.getString("game_name"),
                        rs.getString("game_description"),
                        rs.getInt("minimum_number_of_players"),
                        rs.getInt("maximum_number_of_players"),
                        rs.getInt("minimum_number_of_cpu"),
                        rs.getInt("maximum_number_of_cpu"),
                        rs.getLong("faction_count"),
                        rs.getLong("unit_type_count"),
                        rs.getLong("variant_count"),
                        rs.getLong("object_count")
                ))
                : Optional.empty(), definitionId, definitionId, definitionId, gameId);
    }

    public List<FactionSummary> findFactions(long gameId) {
        return findFactions(gameId, publishedDefinitionId(gameId));
    }

    public List<FactionSummary> findFactions(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT f.faction_id, f.faction_name, f.faction_description,
                       count(DISTINCT uc.unit_classification_id) AS classification_count,
                       count(DISTINCT ut.unit_type_id) AS unit_type_count,
                       count(DISTINCT uv.unit_variant_id) AS variant_count
                FROM game_engine.faction f
                LEFT JOIN game_engine.unit_classification uc ON uc.faction_id = f.faction_id
                LEFT JOIN game_engine.unit_type ut ON ut.unit_classification_id = uc.unit_classification_id
                 LEFT JOIN game_engine.unit_variant uv ON uv.unit_type_id = ut.unit_type_id
                 WHERE f.game_id = ?
                   AND f.game_version_id = ?
                 GROUP BY f.faction_id
                ORDER BY f.faction_name
                """, (rs, rowNum) -> new FactionSummary(
                rs.getLong("faction_id"),
                rs.getString("faction_name"),
                rs.getString("faction_description"),
                rs.getLong("classification_count"),
                rs.getLong("unit_type_count"),
                rs.getLong("variant_count")
        ), gameId, definitionId);
    }

    public List<Terrain> findTerrain(long gameId) {
        return findTerrain(gameId, publishedDefinitionId(gameId));
    }

    public List<Terrain> findTerrain(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT terrain_type_id, terrain_type_name, terrain_type,
                       default_characteristic_json::text AS default_characteristic_json
                 FROM game_engine.terrain_type
                 WHERE game_id = ?
                   AND game_version_id = ?
                 ORDER BY terrain_type_name
                """, (rs, rowNum) -> new Terrain(
                rs.getLong("terrain_type_id"),
                rs.getString("terrain_type_name"),
                rs.getString("terrain_type"),
                rs.getString("default_characteristic_json")
        ), gameId, definitionId);
    }

    public List<GameRule> findRules(long gameId) {
        return findRules(gameId, publishedDefinitionId(gameId));
    }

    public List<GameRule> findRules(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT game_rule_id, game_rule_name, game_rule_description,
                       game_rule_characteristic_json::text AS game_rule_characteristic_json
                 FROM game_engine.game_rule
                 WHERE game_id = ?
                   AND game_version_id = ?
                 ORDER BY game_rule_name
                """, (rs, rowNum) -> new GameRule(
                rs.getLong("game_rule_id"),
                rs.getString("game_rule_name"),
                rs.getString("game_rule_description"),
                rs.getString("game_rule_characteristic_json")
        ), gameId, definitionId);
    }

    public List<GameMode> findGameModes(long gameId) {
        return findGameModes(gameId, publishedDefinitionId(gameId));
    }

    public List<GameMode> findGameModes(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT game_mode_id, game_mode_name, game_mode_description,
                       mode_config_json::text AS mode_config_json
                 FROM game_engine.game_mode
                 WHERE game_id = ?
                   AND game_version_id = ?
                   AND game_mode_code <> 'fleet_battle'
                 ORDER BY game_mode_name
                """, (rs, rowNum) -> new GameMode(
                rs.getLong("game_mode_id"),
                rs.getString("game_mode_name"),
                rs.getString("game_mode_description"),
                rs.getString("mode_config_json")
        ), gameId, definitionId);
    }

    public List<GameVersion> findGameVersions(long gameId) {
        return jdbc.query("""
                SELECT gv.game_version_id, gv.version_code, gv.version_name,
                       gv.release_date, gv.source_revision, cs.source_name,
                       cs.source_url
                FROM game_engine.game_version gv
                LEFT JOIN game_engine.content_source cs
                    ON cs.content_source_id = gv.content_source_id
                WHERE gv.game_id = ?
                ORDER BY gv.release_date DESC NULLS LAST, gv.version_name
                """, (rs, rowNum) -> new GameVersion(
                rs.getLong("game_version_id"),
                rs.getString("version_code"),
                rs.getString("version_name"),
                rs.getObject("release_date", java.time.LocalDate.class),
                rs.getString("source_revision"),
                rs.getString("source_name"),
                rs.getString("source_url")
        ), gameId);
    }

    public List<Phase> findPhases(long gameId) {
        return findPhases(gameId, publishedDefinitionId(gameId));
    }

    public List<Phase> findPhases(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT phase_id, phase_code, phase_name, phase_description,
                       phase_config_json::text AS phase_config_json
                 FROM game_engine.phase_definition
                 WHERE game_id = ?
                   AND game_version_id = ?
                 ORDER BY phase_code
                """, (rs, rowNum) -> new Phase(
                rs.getLong("phase_id"), rs.getString("phase_code"),
                rs.getString("phase_name"), rs.getString("phase_description"),
                rs.getString("phase_config_json")
        ), gameId, definitionId);
    }

    public List<Action> findActions(long gameId) {
        return findActions(gameId, publishedDefinitionId(gameId));
    }

    public List<Action> findActions(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT action_id, action_code, action_name, action_description,
                       action_config_json::text AS action_config_json
                 FROM game_engine.action_definition
                 WHERE game_id = ?
                   AND game_version_id = ?
                 ORDER BY action_name
                """, (rs, rowNum) -> new Action(
                rs.getLong("action_id"), rs.getString("action_code"),
                rs.getString("action_name"), rs.getString("action_description"),
                rs.getString("action_config_json")
        ), gameId, definitionId);
    }

    public List<Scenario> findScenarios(long gameId) {
        return findScenarios(gameId, publishedDefinitionId(gameId));
    }

    public List<Scenario> findScenarios(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT s.scenario_id, s.scenario_code, s.scenario_name,
                       s.scenario_description,
                       s.scenario_config_json::text AS scenario_config_json,
                       count(DISTINCT sb.board_definition_id) AS board_count,
                       count(DISTINCT sgo.game_object_id) AS object_count
                FROM game_engine.scenario_definition s
                LEFT JOIN game_engine.scenario_board sb ON sb.scenario_id = s.scenario_id
                 LEFT JOIN game_engine.scenario_game_object sgo ON sgo.scenario_id = s.scenario_id
                 WHERE s.game_id = ?
                   AND s.game_version_id = ?
                 GROUP BY s.scenario_id
                ORDER BY s.scenario_name
                """, (rs, rowNum) -> new Scenario(
                rs.getLong("scenario_id"), rs.getString("scenario_code"),
                rs.getString("scenario_name"), rs.getString("scenario_description"),
                rs.getString("scenario_config_json"), rs.getLong("board_count"),
                rs.getLong("object_count")
        ), gameId, definitionId);
    }

    public List<Board> findBoards(long gameId) {
        return findBoards(gameId, publishedDefinitionId(gameId));
    }

    public List<Board> findBoards(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT board_definition_id, board_code, board_name, width, height,
                       coordinate_system, board_config_json::text AS board_config_json
                 FROM game_engine.board_definition
                 WHERE game_id = ?
                   AND game_version_id = ?
                 ORDER BY board_name
                """, (rs, rowNum) -> new Board(
                rs.getLong("board_definition_id"), rs.getString("board_code"),
                rs.getString("board_name"), rs.getBigDecimal("width"),
                rs.getBigDecimal("height"), rs.getString("coordinate_system"),
                rs.getString("board_config_json")
        ), gameId, definitionId);
    }

    public List<FleetFormat> findFleetFormats(long gameId) {
        return findFleetFormats(gameId, publishedDefinitionId(gameId));
    }

    public List<FleetFormat> findFleetFormats(long gameId, long definitionId) {
        return jdbc.query("""
                 SELECT ff.fleet_format_id, ff.format_code, ff.format_name,
                        ff.points_limit, gm.game_mode_id, gm.game_mode_name,
                       ff.format_config_json::text AS format_config_json,
                       count(DISTINCT fff.faction_id) AS faction_count,
                       count(DISTINCT ful.fleet_unit_limit_id) AS limit_count,
                       COALESCE(jsonb_agg(DISTINCT jsonb_build_object(
                           'classification', uc.unit_classification_name,
                           'minimum', ful.minimum_count,
                           'maximum', ful.maximum_count,
                           'configuration', ful.limit_config_json
                       )) FILTER (WHERE ful.fleet_unit_limit_id IS NOT NULL), '[]'::jsonb)::text AS limits
                FROM game_engine.fleet_format ff
                JOIN game_engine.game_mode gm ON gm.game_mode_id = ff.game_mode_id
                LEFT JOIN game_engine.fleet_format_faction fff
                    ON fff.fleet_format_id = ff.fleet_format_id
                LEFT JOIN game_engine.fleet_unit_limit ful
                    ON ful.fleet_format_id = ff.fleet_format_id
                 LEFT JOIN game_engine.unit_classification uc
                     ON uc.unit_classification_id = ful.unit_classification_id
                  WHERE gm.game_id = ?
                    AND gm.game_version_id = ?
                    AND gm.game_mode_code <> 'fleet_battle'
                  GROUP BY ff.fleet_format_id, gm.game_mode_id, gm.game_mode_name
                ORDER BY ff.format_name, gm.game_mode_name
                """, (rs, rowNum) -> new FleetFormat(
                rs.getLong("fleet_format_id"), rs.getString("format_code"),
                 rs.getString("format_name"), rs.getInt("points_limit"), rs.getLong("game_mode_id"),
                 rs.getString("game_mode_name"), rs.getString("format_config_json"),
                rs.getLong("faction_count"), rs.getLong("limit_count"),
                rs.getString("limits")
         ), gameId, definitionId);
    }

    public List<GameObjectTypeSummary> findObjectTypes(long gameId) {
        return findObjectTypes(gameId, publishedDefinitionId(gameId));
    }

    public List<GameObjectTypeSummary> findObjectTypes(long gameId, long definitionId) {
        return jdbc.query("""
                SELECT got.game_object_type_id, got.object_type_code,
                       got.object_type_name, got.object_kind,
                       count(go.game_object_id) AS object_count
                FROM game_engine.game_object_type got
                 LEFT JOIN game_engine.game_object go
                     ON go.game_object_type_id = got.game_object_type_id
                 WHERE got.game_id = ?
                   AND got.game_version_id = ?
                 GROUP BY got.game_object_type_id
                ORDER BY got.object_kind, got.object_type_name
                """, (rs, rowNum) -> new GameObjectTypeSummary(
                rs.getLong("game_object_type_id"), rs.getString("object_type_code"),
                rs.getString("object_type_name"), rs.getString("object_kind"),
                rs.getLong("object_count")
         ), gameId, definitionId);
    }

    public List<GameObjectSummary> findObjects(long gameId, String type, String query) {
        return findObjects(gameId, type, query, publishedDefinitionId(gameId));
    }

    public List<GameObjectSummary> findObjects(long gameId, String type, String query, long definitionId) {
        return jdbc.query("""
                SELECT go.game_object_id, got.object_type_code, got.object_type_name,
                       go.object_code, go.object_name, go.object_description,
                       go.display_text, go.object_data_json::text AS object_data_json,
                       go.source_reference_json::text AS source_reference_json
                FROM game_engine.game_object go
                 JOIN game_engine.game_object_type got
                     ON got.game_object_type_id = go.game_object_type_id
                 WHERE go.game_id = ?
                   AND go.game_version_id = ?
                   AND (? = '' OR got.object_type_code = ?)
                  AND (? = '' OR go.object_name ILIKE '%' || ? || '%'
                       OR go.object_code ILIKE '%' || ? || '%')
                ORDER BY got.object_type_name, go.object_name, go.game_object_id
                """, (rs, rowNum) -> new GameObjectSummary(
                rs.getLong("game_object_id"), rs.getString("object_type_code"),
                rs.getString("object_type_name"), rs.getString("object_code"),
                rs.getString("object_name"), rs.getString("object_description"),
                rs.getString("display_text"), rs.getString("object_data_json"),
                rs.getString("source_reference_json")
         ), gameId, definitionId, type, type, query, query, query);
    }

    public Optional<GameObjectPage> findObject(long objectId) {
        Optional<GameObjectPage> object = jdbc.query("""
                SELECT go.game_object_id, go.game_id, go.game_version_id, got.object_type_code,
                       got.object_type_name, go.object_code, go.object_name,
                       go.object_description, go.display_text,
                       go.object_data_json::text AS object_data_json,
                       go.source_reference_json::text AS source_reference_json
                FROM game_engine.game_object go
                JOIN game_engine.game_object_type got
                    ON got.game_object_type_id = go.game_object_type_id
                WHERE go.game_object_id = ?
                """, rs -> rs.next()
                ? Optional.of(new GameObjectPage(
                         rs.getLong("game_object_id"), rs.getLong("game_id"),
                         rs.getLong("game_version_id"),
                        rs.getString("object_type_code"), rs.getString("object_type_name"),
                        rs.getString("object_code"), rs.getString("object_name"),
                        rs.getString("object_description"), rs.getString("display_text"),
                        rs.getString("object_data_json"), rs.getString("source_reference_json")
                ))
                : Optional.empty(), objectId);

        object.ifPresent(page -> page.eligibility.addAll(jdbc.query("""
                SELECT goe.eligibility_type,
                       COALESCE(f.faction_name, uc.unit_classification_name,
                                ut.unit_type_name, uv.unit_variant_name,
                                gm.game_mode_name) AS target_name,
                       goe.condition_json::text AS condition_json
                FROM game_engine.game_object_eligibility goe
                LEFT JOIN game_engine.faction f ON f.faction_id = goe.faction_id
                LEFT JOIN game_engine.unit_classification uc
                    ON uc.unit_classification_id = goe.unit_classification_id
                LEFT JOIN game_engine.unit_type ut ON ut.unit_type_id = goe.unit_type_id
                LEFT JOIN game_engine.unit_variant uv ON uv.unit_variant_id = goe.unit_variant_id
                LEFT JOIN game_engine.game_mode gm ON gm.game_mode_id = goe.game_mode_id
                WHERE goe.game_object_id = ?
                ORDER BY target_name
                """, (rs, rowNum) -> new Eligibility(
                rs.getString("eligibility_type"), rs.getString("target_name"),
                rs.getString("condition_json")
        ), objectId)));

        object.ifPresent(page -> page.associations.addAll(jdbc.query("""
                SELECT 'Unit variant' AS owner_type, uv.unit_variant_name AS owner_name,
                       uvo.usage_code, uvo.quantity, uvo.usage_config_json::text AS config
                FROM game_engine.unit_variant_game_object uvo
                JOIN game_engine.unit_variant uv ON uv.unit_variant_id = uvo.unit_variant_id
                WHERE uvo.game_object_id = ?
                UNION ALL
                SELECT 'Game mode', gm.game_mode_name, mgo.usage_code,
                       mgo.quantity, mgo.usage_config_json::text
                FROM game_engine.game_mode_game_object mgo
                JOIN game_engine.game_mode gm ON gm.game_mode_id = mgo.game_mode_id
                WHERE mgo.game_object_id = ?
                UNION ALL
                SELECT 'Scenario', s.scenario_name, sgo.usage_code,
                       sgo.quantity, sgo.usage_config_json::text
                FROM game_engine.scenario_game_object sgo
                JOIN game_engine.scenario_definition s ON s.scenario_id = sgo.scenario_id
                WHERE sgo.game_object_id = ?
                ORDER BY owner_type, owner_name
                """, (rs, rowNum) -> new ObjectAssociation(
                rs.getString("owner_type"), rs.getString("owner_name"),
                rs.getString("usage_code"), rs.getInt("quantity"),
                rs.getString("config")
        ), objectId, objectId, objectId)));

        return object;
    }

    public void updateGame(long gameId, String name, String description,
                           int minimumPlayers, int maximumPlayers,
                           int minimumCpu, int maximumCpu) {
        jdbc.update("""
                UPDATE game_engine.game
                SET game_name = ?, game_description = ?,
                    minimum_number_of_players = ?, maximum_number_of_players = ?,
                    minimum_number_of_cpu = ?, maximum_number_of_cpu = ?
                WHERE game_id = ?
                """, name, description, minimumPlayers, maximumPlayers,
                minimumCpu, maximumCpu, gameId);
    }

    public void updateObject(long objectId, String name, String description,
                             String displayText, String data, String sourceReference) {
        jdbc.update("""
                UPDATE game_engine.game_object
                SET object_name = ?, object_description = ?, display_text = ?,
                    object_data_json = ?::jsonb, source_reference_json = ?::jsonb
                WHERE game_object_id = ?
                """, name, description, displayText, data, sourceReference, objectId);
    }

    public Optional<FactionPage> findFaction(long factionId) {
        Optional<FactionPage> faction = jdbc.query("""
                SELECT f.faction_id, f.game_id, f.faction_name, f.faction_description,
                       g.game_name
                FROM game_engine.faction f
                JOIN game_engine.game g ON g.game_id = f.game_id
                WHERE f.faction_id = ?
                """, rs -> rs.next()
                ? Optional.of(new FactionPage(
                        rs.getLong("faction_id"),
                        rs.getLong("game_id"),
                        rs.getString("game_name"),
                        rs.getString("faction_name"),
                        rs.getString("faction_description")
                ))
                : Optional.empty(), factionId);

        faction.ifPresent(page -> page.classifications.addAll(jdbc.query("""
                SELECT uc.unit_classification_id, uc.unit_classification_name,
                       uc.unit_classification_description,
                       count(DISTINCT ut.unit_type_id) AS unit_type_count
                FROM game_engine.unit_classification uc
                LEFT JOIN game_engine.unit_type ut ON ut.unit_classification_id = uc.unit_classification_id
                WHERE uc.faction_id = ?
                GROUP BY uc.unit_classification_id
                ORDER BY uc.unit_classification_name
                """, (rs, rowNum) -> new Classification(
                rs.getLong("unit_classification_id"),
                rs.getString("unit_classification_name"),
                rs.getString("unit_classification_description"),
                rs.getLong("unit_type_count")
        ), factionId)));

        faction.ifPresent(page -> page.classifications.forEach(classification ->
                classification.unitTypes.addAll(jdbc.query("""
                        SELECT ut.unit_type_id, ut.unit_type_name, ut.unit_type_description,
                               count(uv.unit_variant_id) AS variant_count
                        FROM game_engine.unit_type ut
                        LEFT JOIN game_engine.unit_variant uv ON uv.unit_type_id = ut.unit_type_id
                        WHERE ut.unit_classification_id = ?
                        GROUP BY ut.unit_type_id
                        ORDER BY ut.unit_type_name
                        """, (rs, rowNum) -> new UnitTypeSummary(
                        rs.getLong("unit_type_id"),
                        rs.getString("unit_type_name"),
                        rs.getString("unit_type_description"),
                        rs.getLong("variant_count")
                ), classification.classificationId))));

        return faction;
    }

    public Optional<UnitTypePage> findUnitType(long unitTypeId) {
        Optional<UnitTypePage> unitType = jdbc.query("""
                SELECT ut.unit_type_id, ut.unit_type_name, ut.unit_type_description,
                       uc.unit_classification_id, uc.unit_classification_name,
                       f.faction_id, f.faction_name, f.game_id, g.game_name
                FROM game_engine.unit_type ut
                JOIN game_engine.unit_classification uc ON uc.unit_classification_id = ut.unit_classification_id
                JOIN game_engine.faction f ON f.faction_id = uc.faction_id
                JOIN game_engine.game g ON g.game_id = f.game_id
                WHERE ut.unit_type_id = ?
                """, rs -> rs.next()
                ? Optional.of(new UnitTypePage(
                        rs.getLong("unit_type_id"),
                        rs.getString("unit_type_name"),
                        rs.getString("unit_type_description"),
                        rs.getLong("unit_classification_id"),
                        rs.getString("unit_classification_name"),
                        rs.getLong("faction_id"),
                        rs.getString("faction_name"),
                        rs.getLong("game_id"),
                        rs.getString("game_name")
                ))
                : Optional.empty(), unitTypeId);

        unitType.ifPresent(page -> {
            Map<Long, VariantDetail> variants = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT uv.unit_variant_id, uv.unit_variant_name,
                           uv.unit_variant_description,
                           ct.characteristic_type_name,
                           uvc.characteristic_json::text AS characteristic_json
                    FROM game_engine.unit_variant uv
                    LEFT JOIN game_engine.unit_variant_characteristic uvc
                        ON uvc.unit_variant_id = uv.unit_variant_id
                    LEFT JOIN game_engine.characteristic_type ct
                        ON ct.characteristic_type_id = uvc.characteristic_type_id
                    WHERE uv.unit_type_id = ?
                    ORDER BY uv.unit_variant_name, ct.characteristic_type_name
                    """, rs -> {
                long variantId = rs.getLong("unit_variant_id");
                VariantDetail variant = variants.get(variantId);
                if (variant == null) {
                    variant = new VariantDetail(
                            variantId,
                            rs.getString("unit_variant_name"),
                            rs.getString("unit_variant_description")
                    );
                    variants.put(variantId, variant);
                }
                if (rs.getString("characteristic_type_name") != null) {
                    variant.characteristics.add(new Characteristic(
                            rs.getString("characteristic_type_name"),
                            rs.getString("characteristic_json")
                    ));
                }
            }, unitTypeId);
            page.variants.addAll(new ArrayList<>(variants.values()));
        });

        return unitType;
    }

    public record GameSummary(
            long gameId,
            String gameName,
            String gameDescription,
            int minimumNumberOfPlayers,
            int maximumNumberOfPlayers,
            int minimumNumberOfCpu,
            int maximumNumberOfCpu,
            long factionCount,
            long unitTypeCount,
            long variantCount,
            long objectCount
    ) {
    }

    public record FactionSummary(
            long factionId,
            String factionName,
            String factionDescription,
            long classificationCount,
            long unitTypeCount,
            long variantCount
    ) {
    }

    public record Terrain(long terrainTypeId, String name, String type, String characteristics) {
    }

    public record GameRule(long gameRuleId, String name, String description, String characteristics) {
    }

    public record GameMode(long gameModeId, String name, String description, String configuration) {
    }

    public record GameVersion(long versionId, String code, String name,
                              java.time.LocalDate releaseDate, String sourceRevision,
                              String sourceName, String sourceUrl) {
    }

    public record Phase(long phaseId, String code, String name, String description,
                        String configuration) {
    }

    public record Action(long actionId, String code, String name, String description,
                         String configuration) {
    }

    public record Scenario(long scenarioId, String code, String name, String description,
                           String configuration, long boardCount, long objectCount) {
    }

    public record Board(long boardId, String code, String name,
                        java.math.BigDecimal width, java.math.BigDecimal height,
                        String coordinateSystem, String configuration) {
    }

    public record FleetFormat(long fleetFormatId, String code, String name,
                               int pointsLimit, long gameModeId, String gameModeName, String configuration,
                               long factionCount, long limitCount, String limits) {
    }

    public record GameObjectTypeSummary(long typeId, String code, String name,
                                        String kind, long objectCount) {
    }

    public record GameObjectSummary(long objectId, String typeCode, String typeName,
                                    String code, String name, String description,
                                    String displayText, String data, String sourceReference) {
    }

    public record Eligibility(String type, String targetName, String condition) {
    }

    public record ObjectAssociation(String ownerType, String ownerName, String usageCode,
                                    int quantity, String configuration) {
    }

    public record UnitTypeSummary(long unitTypeId, String name, String description, long variantCount) {
    }

    public record Characteristic(String name, String data) {
    }

    public static final class FactionPage {
        private final long factionId;
        private final long gameId;
        private final String gameName;
        private final String factionName;
        private final String factionDescription;
        private final List<Classification> classifications = new ArrayList<>();

        private FactionPage(long factionId, long gameId, String gameName, String factionName, String factionDescription) {
            this.factionId = factionId;
            this.gameId = gameId;
            this.gameName = gameName;
            this.factionName = factionName;
            this.factionDescription = factionDescription;
        }

        public long getFactionId() { return factionId; }
        public long getGameId() { return gameId; }
        public String getGameName() { return gameName; }
        public String getFactionName() { return factionName; }
        public String getFactionDescription() { return factionDescription; }
        public List<Classification> getClassifications() { return classifications; }
    }

    public static final class Classification {
        private final long classificationId;
        private final String name;
        private final String description;
        private final long unitTypeCount;
        private final List<UnitTypeSummary> unitTypes = new ArrayList<>();

        private Classification(long classificationId, String name, String description, long unitTypeCount) {
            this.classificationId = classificationId;
            this.name = name;
            this.description = description;
            this.unitTypeCount = unitTypeCount;
        }

        public long getClassificationId() { return classificationId; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public long getUnitTypeCount() { return unitTypeCount; }
        public List<UnitTypeSummary> getUnitTypes() { return unitTypes; }
    }

    public static final class UnitTypePage {
        private final long unitTypeId;
        private final String unitTypeName;
        private final String unitTypeDescription;
        private final long classificationId;
        private final String classificationName;
        private final long factionId;
        private final String factionName;
        private final long gameId;
        private final String gameName;
        private final List<VariantDetail> variants = new ArrayList<>();

        private UnitTypePage(long unitTypeId, String unitTypeName, String unitTypeDescription,
                             long classificationId, String classificationName, long factionId,
                             String factionName, long gameId, String gameName) {
            this.unitTypeId = unitTypeId;
            this.unitTypeName = unitTypeName;
            this.unitTypeDescription = unitTypeDescription;
            this.classificationId = classificationId;
            this.classificationName = classificationName;
            this.factionId = factionId;
            this.factionName = factionName;
            this.gameId = gameId;
            this.gameName = gameName;
        }

        public long getUnitTypeId() { return unitTypeId; }
        public String getUnitTypeName() { return unitTypeName; }
        public String getUnitTypeDescription() { return unitTypeDescription; }
        public long getClassificationId() { return classificationId; }
        public String getClassificationName() { return classificationName; }
        public long getFactionId() { return factionId; }
        public String getFactionName() { return factionName; }
        public long getGameId() { return gameId; }
        public String getGameName() { return gameName; }
        public List<VariantDetail> getVariants() { return variants; }
    }

    public static final class VariantDetail {
        private final long variantId;
        private final String name;
        private final String description;
        private final List<Characteristic> characteristics = new ArrayList<>();

        private VariantDetail(long variantId, String name, String description) {
            this.variantId = variantId;
            this.name = name;
            this.description = description;
        }

        public long getVariantId() { return variantId; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public List<Characteristic> getCharacteristics() { return characteristics; }
    }

    public static final class GameObjectPage {
        private final long objectId;
        private final long gameId;
        private final long gameVersionId;
        private final String typeCode;
        private final String typeName;
        private final String code;
        private final String name;
        private final String description;
        private final String displayText;
        private final String data;
        private final String sourceReference;
        private final List<Eligibility> eligibility = new ArrayList<>();
        private final List<ObjectAssociation> associations = new ArrayList<>();

        private GameObjectPage(long objectId, long gameId, long gameVersionId,
                               String typeCode, String typeName,
                               String code, String name, String description, String displayText,
                               String data, String sourceReference) {
            this.objectId = objectId;
            this.gameId = gameId;
            this.gameVersionId = gameVersionId;
            this.typeCode = typeCode;
            this.typeName = typeName;
            this.code = code;
            this.name = name;
            this.description = description;
            this.displayText = displayText;
            this.data = data;
            this.sourceReference = sourceReference;
        }

        public long getObjectId() { return objectId; }
        public long getGameId() { return gameId; }
        public long getGameVersionId() { return gameVersionId; }
        public String getTypeCode() { return typeCode; }
        public String getTypeName() { return typeName; }
        public String getCode() { return code; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getDisplayText() { return displayText; }
        public String getData() { return data; }
        public String getSourceReference() { return sourceReference; }
        public List<Eligibility> getEligibility() { return eligibility; }
        public List<ObjectAssociation> getAssociations() { return associations; }
    }
}
