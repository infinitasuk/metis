package com.metis.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
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
}
