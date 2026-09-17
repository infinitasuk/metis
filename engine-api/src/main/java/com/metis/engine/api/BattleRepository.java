package com.metis.engine.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.stereotype.Repository;

import java.sql.CallableStatement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class BattleRepository {

    private final JdbcTemplate jdbc;

    public BattleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BattleSession> findSessions(Long gameId, String status) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_list(?, ?)", (rs, rowNum) ->
                new BattleSession(
                        rs.getLong("session_id"), rs.getLong("game_id"),
                        rs.getString("game_name"), rs.getString("session_name"),
                        rs.getString("session_code"), rs.getString("visibility"),
                        rs.getObject("owner_user_id", Long.class),
                        rs.getString("status"),
                        rs.getInt("current_round"), rs.getLong("player_count"),
                        rs.getString("created_at"), rs.getString("updated_at")
                ), gameId, status);
    }

    public List<BattleSession> findVisibleSessions(Long gameId, String status, MetisUser user) {
        return jdbc.query("""
                SELECT s.session_id, s.game_id, g.game_name, s.session_name, s.session_code,
                       s.visibility, s.owner_user_id, s.status, s.current_round,
                       count(sp.session_player_id) AS player_count, s.created_at, s.updated_at
                FROM game_instance.game_session s
                JOIN game_engine.game g ON g.game_id = s.game_id
                LEFT JOIN game_instance.session_player sp ON sp.session_id = s.session_id
                WHERE (CAST(? AS bigint) IS NULL OR s.game_id = ?)
                  AND (CAST(? AS text) IS NULL OR s.status = ?)
                  AND (? OR s.visibility = 'public' OR s.owner_user_id = ?
                       OR EXISTS (SELECT 1 FROM game_instance.session_invitation i
                                  WHERE i.session_id = s.session_id AND i.user_id = ?))
                GROUP BY s.session_id, g.game_name
                ORDER BY s.updated_at DESC, s.session_id DESC
                """, (rs, rowNum) -> new BattleSession(
                rs.getLong("session_id"), rs.getLong("game_id"), rs.getString("game_name"),
                rs.getString("session_name"), rs.getString("session_code"), rs.getString("visibility"),
                rs.getObject("owner_user_id", Long.class), rs.getString("status"),
                rs.getInt("current_round"), rs.getLong("player_count"),
                rs.getString("created_at"), rs.getString("updated_at")),
                gameId, gameId, status, status, user.admin(), user.userId(), user.userId());
    }

    public Optional<BattleSessionDetail> findSession(long sessionId) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_get(?)", rs -> rs.next()
                ? Optional.of(new BattleSessionDetail(
                        rs.getLong("session_id"), rs.getLong("game_id"),
                         rs.getString("game_name"), rs.getObject("game_version_id", Long.class),
                        rs.getString("game_version_code"),
                         rs.getObject("game_mode_id", Long.class), rs.getObject("scenario_id", Long.class),
                         rs.getObject("fleet_format_id", Long.class),
                          rs.getString("session_name"), rs.getString("session_code"),
                          rs.getString("visibility"), rs.getObject("owner_user_id", Long.class),
                         rs.getString("status"), rs.getInt("current_round"),
                         rs.getObject("current_phase_id", Long.class),
                         rs.getString("state_json"), rs.getLong("state_version"), rs.getString("engine_version"),
                         rs.getString("api_version"), rs.getInt("event_schema_version"),
                         rs.getString("created_at"), rs.getString("updated_at")
                ))
                : Optional.empty(), sessionId);
    }

    public boolean canViewSession(long sessionId, MetisUser user) {
        Boolean allowed = jdbc.queryForObject("SELECT game_instance.fn_session_can_view(?,?,?)",
                Boolean.class, sessionId, user.userId(), user.admin());
        return Boolean.TRUE.equals(allowed);
    }

    public boolean canEditSession(long sessionId, MetisUser user) {
        Boolean allowed = jdbc.queryForObject("SELECT game_instance.fn_session_can_edit(?,?,?)",
                Boolean.class, sessionId, user.userId(), user.admin());
        return Boolean.TRUE.equals(allowed);
    }

    public void assignSessionOwner(long sessionId, long userId) {
        execute("{call game_instance.sp_session_owner_set(?,?)}", statement -> {
            statement.setLong(1, sessionId);
            statement.setLong(2, userId);
        });
    }

    public void inviteUser(long sessionId, long userId, long invitedByUserId) {
        execute("{call game_instance.sp_session_invite(?,?,?)}", statement -> {
            statement.setLong(1, sessionId);
            statement.setLong(2, userId);
            statement.setLong(3, invitedByUserId);
        });
    }

    public List<SessionInvitation> findInvitations(long sessionId) {
        return jdbc.query("""
                SELECT i.user_id, u.username, u.display_name, i.invited_by_user_id, i.created_at
                FROM game_instance.session_invitation i
                JOIN game_instance.app_user u ON u.user_id = i.user_id
                WHERE i.session_id = ?
                ORDER BY u.display_name
                """, (rs, rowNum) -> new SessionInvitation(rs.getLong("user_id"),
                rs.getString("username"), rs.getString("display_name"),
                rs.getLong("invited_by_user_id"), rs.getString("created_at")), sessionId);
    }

    public Optional<Long> sessionIdForPlayer(long playerId) {
        return sessionId("SELECT session_id FROM game_instance.session_player WHERE session_player_id = ?", playerId);
    }

    public Optional<Long> sessionIdForFleet(long fleetId) {
        return sessionId("""
                SELECT sp.session_id
                FROM game_instance.player_fleet f
                JOIN game_instance.session_player sp ON sp.session_player_id = f.session_player_id
                WHERE f.fleet_id = ?
                """, fleetId);
    }

    public Optional<Long> sessionIdForUnit(long unitId) {
        return sessionId("""
                SELECT sp.session_id
                FROM game_instance.fleet_unit u
                JOIN game_instance.player_fleet f ON f.fleet_id = u.fleet_id
                JOIN game_instance.session_player sp ON sp.session_player_id = f.session_player_id
                WHERE u.unit_instance_id = ?
                """, unitId);
    }

    public Optional<Long> sessionIdForBoard(long boardId) {
        return sessionId("SELECT session_id FROM game_instance.session_board WHERE session_board_id = ?", boardId);
    }

    public Optional<Long> sessionIdForTurn(long turnId) {
        return sessionId("SELECT session_id FROM game_instance.session_turn WHERE turn_id = ?", turnId);
    }

    private Optional<Long> sessionId(String sql, long resourceId) {
        return jdbc.query(sql, rs -> rs.next() ? Optional.of(rs.getLong("session_id")) : Optional.empty(), resourceId);
    }

    public List<SessionPlayer> findPlayers(long sessionId) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_players(?)", (rs, rowNum) ->
                new SessionPlayer(
                        rs.getLong("session_player_id"), rs.getLong("session_id"),
                        rs.getInt("seat_number"), rs.getString("display_name"),
                        rs.getString("player_kind"), rs.getObject("faction_id", Long.class),
                        rs.getString("faction_name"), rs.getBoolean("is_ready"),
                        rs.getString("state_json")
                ), sessionId);
    }

    public Optional<SessionPlayer> findPlayer(long playerId) {
        return jdbc.query("SELECT * FROM game_instance.fn_player_get(?)", rs -> rs.next()
                ? Optional.of(new SessionPlayer(
                        rs.getLong("session_player_id"), rs.getLong("session_id"),
                        rs.getInt("seat_number"), rs.getString("display_name"),
                        rs.getString("player_kind"), rs.getObject("faction_id", Long.class),
                        rs.getString("faction_name"), rs.getBoolean("is_ready"),
                        rs.getString("state_json")
                ))
                : Optional.empty(), playerId);
    }

    public List<SessionUnit> findUnits(long sessionId) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_units(?)", (rs, rowNum) ->
                new SessionUnit(
                        rs.getLong("unit_instance_id"), rs.getLong("session_id"),
                        rs.getLong("session_player_id"), rs.getLong("fleet_id"),
                        rs.getLong("unit_variant_id"), rs.getString("unit_name"),
                        rs.getBigDecimal("position_x"), rs.getBigDecimal("position_y"),
                        rs.getBigDecimal("facing"), rs.getString("unit_status"),
                        rs.getString("state_json")
                ), sessionId);
    }

    public List<SessionEvent> findEvents(long sessionId, long afterSequence) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_events(?, ?)", (rs, rowNum) ->
                new SessionEvent(
                        rs.getLong("event_id"), rs.getLong("event_sequence"),
                        rs.getString("event_type"), rs.getObject("actor_player_id", Long.class),
                        rs.getString("payload_json"), rs.getString("created_at")
                ), sessionId, afterSequence);
    }

    public List<EngineLog> findEngineLogs(long sessionId, long afterLogId) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_engine_logs(?, ?)", (rs, rowNum) ->
                new EngineLog(
                        rs.getLong("log_id"), rs.getLong("session_id"),
                        rs.getString("log_level"), rs.getString("log_source"),
                        rs.getString("message"), rs.getString("context_json"),
                        rs.getString("created_at")
                ), sessionId, afterLogId);
    }

    public List<FleetSummary> findFleets(long sessionId) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_fleets(?)", (rs, rowNum) ->
                new FleetSummary(
                        rs.getLong("fleet_id"), rs.getLong("session_id"),
                        rs.getLong("session_player_id"), rs.getString("player_name"),
                        rs.getObject("fleet_format_id", Long.class), rs.getString("fleet_format_name"),
                        rs.getString("fleet_name"), rs.getObject("points_total", Integer.class),
                        rs.getLong("unit_count"), rs.getString("state_json")
                ), sessionId);
    }

    public List<FleetUnit> findFleetUnits(long fleetId) {
        return jdbc.query("SELECT * FROM game_instance.fn_fleet_units(?)", (rs, rowNum) ->
                new FleetUnit(
                        rs.getLong("unit_instance_id"), rs.getLong("fleet_id"),
                        rs.getLong("unit_variant_id"), rs.getString("unit_variant_name"),
                        rs.getString("unit_name"), rs.getBigDecimal("position_x"),
                        rs.getBigDecimal("position_y"), rs.getBigDecimal("facing"),
                        rs.getString("unit_status"), rs.getString("state_json")
                ), fleetId);
    }

    public List<SessionBoard> findBoards(long sessionId) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_boards(?)", (rs, rowNum) ->
                new SessionBoard(
                        rs.getLong("session_board_id"), rs.getLong("session_id"),
                        rs.getLong("board_definition_id"), rs.getString("board_code"),
                        rs.getString("board_name"), rs.getBigDecimal("board_width"),
                        rs.getBigDecimal("board_height"), rs.getString("board_role"),
                        rs.getString("state_json")
                ), sessionId);
    }

    public List<SessionTurn> findTurns(long sessionId) {
        return jdbc.query("SELECT * FROM game_instance.fn_session_turns(?)", (rs, rowNum) ->
                new SessionTurn(
                        rs.getLong("turn_id"), rs.getLong("session_id"),
                        rs.getInt("round_number"), rs.getObject("phase_id", Long.class),
                        rs.getString("phase_name"), rs.getObject("active_player_id", Long.class),
                        rs.getInt("turn_sequence"), rs.getString("started_at"),
                        rs.getString("ended_at"), rs.getString("state_json")
                ), sessionId);
    }

    public Optional<SessionEvent> findEvent(long eventId) {
        return jdbc.query("SELECT * FROM game_instance.fn_event_get(?)", rs -> rs.next()
                ? Optional.of(new SessionEvent(
                        rs.getLong("event_id"), rs.getLong("event_sequence"),
                        rs.getString("event_type"), rs.getObject("actor_player_id", Long.class),
                        rs.getString("payload_json"), rs.getString("created_at")
                ))
                : Optional.empty(), eventId);
    }

    public long createSession(long gameId, String name, Long gameModeId, Long scenarioId,
                              Long fleetFormatId, String visibility, String engineVersion, String apiVersion,
                              int eventSchemaVersion) {
        return jdbc.execute((CallableStatementCreator) connection -> {
            CallableStatement statement = connection.prepareCall(
                    "{call game_instance.sp_session_create(?,?,?,?,?,?,?,?,?,?)}");
            statement.setLong(1, gameId);
            statement.setString(2, name);
            setNullableLong(statement, 3, gameModeId);
            setNullableLong(statement, 4, scenarioId);
            setNullableLong(statement, 5, fleetFormatId);
            statement.setString(6, visibility);
            setNullableString(statement, 7, engineVersion);
            setNullableString(statement, 8, apiVersion);
            statement.setInt(9, eventSchemaVersion);
            statement.registerOutParameter(10, Types.BIGINT);
            return statement;
        }, (CallableStatementCallback<Long>) statement -> {
            statement.execute();
            return statement.getLong(10);
        });
    }

    public long createFleet(long playerId, Long fleetFormatId, String name, Integer pointsTotal) {
        return callId("{call game_instance.sp_fleet_create(?,?,?,?,?)}", 5, statement -> {
            statement.setLong(1, playerId);
            setNullableLong(statement, 2, fleetFormatId);
            statement.setString(3, name);
            setNullableInt(statement, 4, pointsTotal);
        });
    }

    public void updateFleet(long fleetId, String name, Integer pointsTotal, String stateJson) {
        execute("{call game_instance.sp_fleet_update(?,?,?,?)}", statement -> {
            statement.setLong(1, fleetId);
            setNullableString(statement, 2, name);
            setNullableInt(statement, 3, pointsTotal);
            setNullableString(statement, 4, stateJson);
        });
    }

    public void deleteFleet(long fleetId) {
        execute("{call game_instance.sp_fleet_delete(?)}", statement -> statement.setLong(1, fleetId));
    }

    public long createUnit(long fleetId, long unitVariantId, String name,
                           java.math.BigDecimal positionX, java.math.BigDecimal positionY,
                           java.math.BigDecimal facing, String status, String stateJson) {
        return callId("{call game_instance.sp_unit_create(?,?,?,?,?,?,?,?,?)}", 9, statement -> {
            statement.setLong(1, fleetId);
            statement.setLong(2, unitVariantId);
            statement.setString(3, name);
            setNullableDecimal(statement, 4, positionX);
            setNullableDecimal(statement, 5, positionY);
            setNullableDecimal(statement, 6, facing);
            setNullableString(statement, 7, status);
            setNullableString(statement, 8, stateJson);
        });
    }

    public void updateUnit(long unitId, String name, java.math.BigDecimal positionX,
                           java.math.BigDecimal positionY, java.math.BigDecimal facing,
                           String status, String stateJson) {
        execute("{call game_instance.sp_unit_update(?,?,?,?,?,?,?)}", statement -> {
            statement.setLong(1, unitId);
            setNullableString(statement, 2, name);
            setNullableDecimal(statement, 3, positionX);
            setNullableDecimal(statement, 4, positionY);
            setNullableDecimal(statement, 5, facing);
            setNullableString(statement, 6, status);
            setNullableString(statement, 7, stateJson);
        });
    }

    public void deleteUnit(long unitId) {
        execute("{call game_instance.sp_unit_delete(?)}", statement -> statement.setLong(1, unitId));
    }

    public long createBoard(long sessionId, long boardDefinitionId, String role, String stateJson) {
        return callId("{call game_instance.sp_board_create(?,?,?,?,?)}", 5, statement -> {
            statement.setLong(1, sessionId);
            statement.setLong(2, boardDefinitionId);
            setNullableString(statement, 3, role);
            setNullableString(statement, 4, stateJson);
        });
    }

    public void updateBoard(long boardId, String role, String stateJson) {
        execute("{call game_instance.sp_board_update(?,?,?)}", statement -> {
            statement.setLong(1, boardId);
            setNullableString(statement, 2, role);
            setNullableString(statement, 3, stateJson);
        });
    }

    public void deleteBoard(long boardId) {
        execute("{call game_instance.sp_board_delete(?)}", statement -> statement.setLong(1, boardId));
    }

    public long createTurn(long sessionId, int roundNumber, Long phaseId, Long activePlayerId) {
        return callId("{call game_instance.sp_turn_create(?,?,?,?,?)}", 5, statement -> {
            statement.setLong(1, sessionId);
            statement.setInt(2, roundNumber);
            setNullableLong(statement, 3, phaseId);
            setNullableLong(statement, 4, activePlayerId);
        });
    }

    public void updateTurn(long turnId, Boolean ended, String stateJson) {
        execute("{call game_instance.sp_turn_update(?,?,?)}", statement -> {
            statement.setLong(1, turnId);
            if (ended == null) statement.setNull(2, Types.BOOLEAN); else statement.setBoolean(2, ended);
            setNullableString(statement, 3, stateJson);
        });
    }

    public void deleteTurn(long turnId) {
        execute("{call game_instance.sp_turn_delete(?)}", statement -> statement.setLong(1, turnId));
    }

    public long appendEvent(long sessionId, String type, Long actorPlayerId, String payloadJson) {
        return callId("{call game_instance.sp_event_append(?,?,?,?,?)}", 5, statement -> {
            statement.setLong(1, sessionId);
            statement.setString(2, type);
            setNullableLong(statement, 3, actorPlayerId);
            setNullableString(statement, 4, payloadJson);
        });
    }

    public long appendEngineLog(long sessionId, String level, String source,
                                String message, String contextJson) {
        return callId("{call game_instance.sp_engine_log_append(?,?,?,?,?,?)}", 6, statement -> {
            statement.setLong(1, sessionId);
            statement.setString(2, level);
            statement.setString(3, source);
            statement.setString(4, message);
            if (contextJson == null) {
                statement.setNull(5, Types.OTHER);
            } else {
                statement.setObject(5, contextJson, Types.OTHER);
            }
        });
    }

    private long callId(String call, int outIndex, StatementConfigurer configurer) {
        return jdbc.execute((CallableStatementCreator) connection -> {
            CallableStatement statement = connection.prepareCall(call);
            configurer.configure(statement);
            statement.registerOutParameter(outIndex, Types.BIGINT);
            return statement;
        }, (CallableStatementCallback<Long>) statement -> {
            statement.execute();
            return statement.getLong(outIndex);
        });
    }

    public void updateSession(long sessionId, String status, Integer currentRound,
                              Long currentPhaseId, String stateJson) {
        execute("{call game_instance.sp_session_update(?,?,?,?,?)}", statement -> {
            statement.setLong(1, sessionId);
            setNullableString(statement, 2, status);
            setNullableInt(statement, 3, currentRound);
            setNullableLong(statement, 4, currentPhaseId);
            setNullableString(statement, 5, stateJson);
        });
    }

    public void saveEngineState(long sessionId, long expectedStateVersion, String status,
                                int currentRound, Long currentPhaseId, String stateJson,
                                String eventType, Long actorPlayerId, String payloadJson) {
        execute("{call game_instance.sp_engine_state_save(?,?,?,?,?,?,?,?,?)}", statement -> {
            statement.setLong(1, sessionId);
            statement.setLong(2, expectedStateVersion);
            statement.setString(3, status);
            statement.setInt(4, currentRound);
            setNullableLong(statement, 5, currentPhaseId);
            statement.setString(6, stateJson);
            statement.setString(7, eventType);
            setNullableLong(statement, 8, actorPlayerId);
            setNullableString(statement, 9, payloadJson);
        });
    }

    public void deleteSession(long sessionId) {
        execute("{call game_instance.sp_session_delete(?)}", statement -> statement.setLong(1, sessionId));
    }

    public long createPlayer(long sessionId, int seatNumber, String displayName,
                             String playerKind, Long factionId) {
        return jdbc.execute((CallableStatementCreator) connection -> {
            CallableStatement statement = connection.prepareCall(
                    "{call game_instance.sp_player_create(?,?,?,?,?,?)}");
            statement.setLong(1, sessionId);
            statement.setInt(2, seatNumber);
            statement.setString(3, displayName);
            setNullableString(statement, 4, playerKind);
            setNullableLong(statement, 5, factionId);
            statement.registerOutParameter(6, Types.BIGINT);
            return statement;
        }, (CallableStatementCallback<Long>) statement -> {
            statement.execute();
            return statement.getLong(6);
        });
    }

    public void updatePlayer(long playerId, String displayName, String playerKind,
                             Long factionId, Boolean ready, String stateJson) {
        execute("{call game_instance.sp_player_update(?,?,?,?,?,?)}", statement -> {
            statement.setLong(1, playerId);
            setNullableString(statement, 2, displayName);
            setNullableString(statement, 3, playerKind);
            setNullableLong(statement, 4, factionId);
            if (ready == null) statement.setNull(5, Types.BOOLEAN); else statement.setBoolean(5, ready);
            setNullableString(statement, 6, stateJson);
        });
    }

    public void deletePlayer(long playerId) {
        execute("{call game_instance.sp_player_delete(?)}", statement -> statement.setLong(1, playerId));
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

    private static void setNullableLong(CallableStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    private static void setNullableInt(CallableStatement statement, int index, Integer value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setInt(index, value);
    }

    private static void setNullableString(CallableStatement statement, int index, String value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static void setNullableDecimal(CallableStatement statement, int index, java.math.BigDecimal value) throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.NUMERIC); else statement.setBigDecimal(index, value);
    }

    @FunctionalInterface
    private interface StatementConfigurer {
        void configure(CallableStatement statement) throws java.sql.SQLException;
    }

    public record BattleSession(long sessionId, long gameId, String gameName,
                                String sessionName, String sessionCode, String visibility, Long ownerUserId,
                                String status,
                                int currentRound, long playerCount,
                                String createdAt, String updatedAt) {}

    public record BattleSessionDetail(long sessionId, long gameId, String gameName,
                                      Long gameVersionId, String gameVersionCode,
                                      Long gameModeId, Long scenarioId,
                                      Long fleetFormatId,
                                       String sessionName, String sessionCode, String visibility, Long ownerUserId,
                                       String status,
                                       int currentRound, Long currentPhaseId, String state, long stateVersion,
                                      String engineVersion, String apiVersion,
                                      int eventSchemaVersion, String createdAt, String updatedAt) {}

    public record SessionInvitation(long userId, String username, String displayName,
                                    long invitedByUserId, String createdAt) {}

    public record SessionPlayer(long sessionPlayerId, long sessionId, int seatNumber,
                                String displayName, String playerKind, Long factionId,
                                String factionName, boolean ready, String state) {}

    public record SessionUnit(long unitInstanceId, long sessionId, long sessionPlayerId,
                              long fleetId, long unitVariantId, String unitName,
                              java.math.BigDecimal positionX, java.math.BigDecimal positionY,
                              java.math.BigDecimal facing, String status, String state) {}

    public record SessionEvent(long eventId, long sequence, String type,
                               Long actorPlayerId, String payload, String createdAt) {}

    public record EngineLog(long logId, long sessionId, String level, String source,
                            String message, String context, String createdAt) {}

    public record FleetSummary(long fleetId, long sessionId, long sessionPlayerId,
                               String playerName, Long fleetFormatId, String fleetFormatName,
                               String fleetName, Integer pointsTotal, long unitCount,
                               String state) {}

    public record FleetUnit(long unitInstanceId, long fleetId, long unitVariantId,
                            String unitVariantName, String unitName,
                            java.math.BigDecimal positionX, java.math.BigDecimal positionY,
                            java.math.BigDecimal facing, String status, String state) {}

    public record SessionBoard(long sessionBoardId, long sessionId, long boardDefinitionId,
                               String boardCode, String boardName,
                               java.math.BigDecimal width, java.math.BigDecimal height,
                               String role, String state) {}

    public record SessionTurn(long turnId, long sessionId, int roundNumber, Long phaseId,
                              String phaseName, Long activePlayerId, int sequence,
                              String startedAt, String endedAt, String state) {}
}
