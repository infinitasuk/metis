package com.metis.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.api.BattleRepository.BattleSession;
import com.metis.engine.api.BattleRepository.BattleSessionDetail;
import com.metis.engine.api.BattleRepository.FleetUnit;
import com.metis.engine.api.BattleRepository.FleetSummary;
import com.metis.engine.core.Action;
import com.metis.engine.core.ActionResult;
import com.metis.engine.core.AuthoritativeGameEngine;
import com.metis.engine.core.BoardState;
import com.metis.engine.core.GameDefinition;
import com.metis.engine.core.GameState;
import com.metis.engine.core.LegalAction;
import com.metis.engine.core.ManeuverProfile;
import com.metis.engine.core.MovementCalculator;
import com.metis.engine.core.Player;
import com.metis.engine.core.RandomSource;
import com.metis.engine.core.UnitDefinition;
import com.metis.engine.core.UnitState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Advances sessions whose players are all CPU-controlled at a human-readable pace. */
@Component
public class CpuBattleRunner {
    private static final int UNITS_PER_PLAYER = 2;
    private static final double DEPLOYMENT_EDGE = .10;
    private static final int DEPLOYMENT_ROWS = 4;
    private static final double DEPLOYMENT_ROW_MARGIN = .14;
    private static final double DEPLOYMENT_ROW_STEP = .24;
    private static final double DEPLOYMENT_DEPTH_STEP = 200;
    private static final Logger log = LoggerFactory.getLogger(CpuBattleRunner.class);

    private final BattleRepository battle;
    private final DefinitionRepository definitions;
    private final ObjectMapper objectMapper;
    private final AuthoritativeGameEngine engine = new AuthoritativeGameEngine();

    public CpuBattleRunner(BattleRepository battle, DefinitionRepository definitions, ObjectMapper objectMapper) {
        this.battle = battle;
        this.definitions = definitions;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${metis.cpu.tick-ms:1200}", initialDelayString = "${metis.cpu.initial-delay-ms:1000}")
    public void advanceCpuBattles() {
        for (BattleSession session : battle.findSessions(null, "running")) {
            try {
                advance(session.sessionId());
            } catch (DataAccessException exception) {
                // A human action may have won the state-version race; the next tick will retry.
                log.debug("CPU action skipped for session {}", session.sessionId(), exception);
            } catch (RuntimeException exception) {
                log.warn("CPU action failed for session {}", session.sessionId(), exception);
            }
        }
    }

    private void advance(long sessionId) {
        BattleSessionDetail session = battle.findSession(sessionId).orElse(null);
        if (session == null || session.state() == null || "{}".equals(session.state().trim())) {
            return;
        }
        GameState state = readState(session.state());
        if (state.players().isEmpty() || state.players().stream().anyMatch(player -> !"cpu".equals(player.kind()))) {
            return;
        }
        GameDefinition definition = definitions.findEngineDefinition(session.gameVersionId()).orElse(null);
        if (definition == null) {
            return;
        }
        Action action = chooseAction(definition, state);
        if (action == null) {
            return;
        }
        ActionResult result = engine.applyAction(definition, state, action);
        if (!result.accepted()) {
            log.warn("CPU action {} rejected for session {}: {}", action.type(), sessionId, result.message());
            appendCpuLog(sessionId, action, "WARN", "ACTION_REJECTED", result.message());
            if ("move_unit".equals(action.type())) {
                persistAcceptedAction(sessionId, state, new Action("end_turn", state.activePlayerId(), Map.of()));
            }
            return;
        }
        appendCpuLog(sessionId, action, "INFO", "ACTION_ACCEPTED", result.message());
        persistAcceptedAction(sessionId, state, action, result.resultingState());
        if ("deploy_unit".equals(action.type())) {
            syncFleetLayout(sessionId, action, result.resultingState());
        }
    }

    private void persistAcceptedAction(long sessionId, GameState state, Action action) {
        ActionResult result = engine.applyAction(definitions.findEngineDefinition(state.definitionVersionId()).orElseThrow(), state, action);
        if (result.accepted()) {
            persistAcceptedAction(sessionId, state, action, result.resultingState());
        }
    }

    private void persistAcceptedAction(long sessionId, GameState state, Action action, GameState next) {
        battle.saveEngineState(sessionId, state.stateVersion(), next.status(), next.round(), null,
                writeState(next), next.events().getLast().type(), next.events().getLast().actorPlayerId(),
                writePayload(next.events().getLast().payload()));
    }

    private void appendCpuLog(long sessionId, Action action, String level, String outcome, String message) {
        try {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            context.put("outcome", outcome);
            context.put("actionType", action.type());
            context.put("actorPlayerId", action.actorPlayerId());
            context.put("parameters", action.parameters());
            battle.appendEngineLog(sessionId, level, "CPU", message,
                    objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException exception) {
            log.warn("Could not persist CPU engine log for session {}", sessionId, exception);
        }
    }

    private Action chooseAction(GameDefinition definition, GameState state) {
        if ("deployment".equals(state.phaseCode())) {
            if (state.players().stream().anyMatch(player -> !deploymentCandidates(definition, state, player).isEmpty())) {
                return deploymentAction(definition, state);
            }
            return new Action("advance_phase", 0, Map.of());
        }
        if ("command".equals(state.phaseCode()) || "status".equals(state.phaseCode())) {
            return new Action("advance_phase", 0, Map.of());
        }
        if ((!"ship".equals(state.phaseCode()) && !"squadron".equals(state.phaseCode()))
                || state.activePlayerId() == null) {
            return null;
        }
        Action attack = attackAction(definition, state);
        if (attack != null) {
            return attack;
        }
        UnitState readyUnit = state.units().stream()
                .filter(unit -> unit.playerId() == state.activePlayerId())
                .filter(unit -> "ready".equals(unit.status()) && unit.hullPoints() > 0)
                .filter(unit -> unitCanActInPhase(definition, state.phaseCode(), unit))
                .findFirst().orElse(null);
        if (readyUnit != null) {
            Action move = moveAction(definition, state, readyUnit);
            if (move != null) {
                return move;
            }
        }
        return new Action("end_turn", state.activePlayerId(), Map.of());
    }

    private Action deploymentAction(GameDefinition definition, GameState state) {
        List<Player> players = state.players().stream()
                .sorted(Comparator.comparingInt(Player::seatNumber)).toList();
        Player player = players.stream()
                .filter(item -> !deploymentCandidates(definition, state, item).isEmpty())
                .findFirst().orElse(null);
        if (player == null) {
            return null;
        }
        List<UnitDefinition> profiles = deploymentCandidates(definition, state, player);
        if (profiles.isEmpty()) {
            return null;
        }
        UnitDefinition profile = chooseDeploymentProfile(profiles, state.units(), player.playerId(),
                bound -> ThreadLocalRandom.current().nextInt(bound));
        int playerUnitCount = (int) state.units().stream().filter(unit -> unit.playerId() == player.playerId()).count();
        int playerIndex = players.indexOf(player);
        double x = deploymentX(state.board(), playerIndex, playerUnitCount);
        double y = deploymentY(state.board(), playerUnitCount);
        long unitId = state.units().stream().mapToLong(UnitState::unitId).max().orElse(0) + 1;
        return new Action("deploy_unit", player.playerId(), Map.of(
                "unitId", unitId,
                "unitCode", profile.code(),
                "positionX", x,
                "positionY", y,
                "facing", playerIndex % 2 == 0 ? 0 : 180));
    }

    static List<UnitDefinition> deploymentCandidates(GameDefinition definition, GameState state, Player player) {
        List<UnitDefinition> profiles = definition.unitDefinitions().stream()
                .filter(unit -> unit.factionCode().equals(player.factionCode())).toList();
        int playerUnitCount = (int) state.units().stream().filter(unit -> unit.playerId() == player.playerId()).count();
        int maximumShips = integerOption(state.values(), "maximumShipsPerPlayer", -1);
        int maximumSquadrons = integerOption(state.values(), "maximumSquadronsPerPlayer", -1);
        if (maximumShips < 0 && maximumSquadrons < 0) {
            return playerUnitCount < UNITS_PER_PLAYER ? profiles : List.of();
        }
        int ships = (int) state.units().stream()
                .filter(unit -> unit.playerId() == player.playerId())
                .filter(unit -> !isSquadron(definition, unit))
                .count();
        int squadrons = playerUnitCount - ships;
        return profiles.stream()
                .filter(profile -> {
                    boolean squadron = "squadron".equalsIgnoreCase(profile.classification());
                    return squadron
                            ? maximumSquadrons < 0 || squadrons < maximumSquadrons
                            : maximumShips < 0 || ships < maximumShips;
                })
                .toList();
    }

    private static boolean isSquadron(GameDefinition definition, UnitState unit) {
        return definition.unitDefinitions().stream()
                .filter(profile -> profile.code().equals(unit.unitCode()))
                .findFirst()
                .map(profile -> "squadron".equalsIgnoreCase(profile.classification()))
                .orElse(false);
    }

    static UnitDefinition chooseDeploymentProfile(List<UnitDefinition> profiles, List<UnitState> units,
                                                  long playerId, RandomSource random) {
        List<String> deployedCodes = units.stream()
                .filter(unit -> unit.playerId() == playerId)
                .map(UnitState::unitCode)
                .toList();
        List<UnitDefinition> unused = profiles.stream()
                .filter(profile -> !deployedCodes.contains(profile.code()))
                .toList();
        List<UnitDefinition> candidates = unused.isEmpty() ? profiles : unused;
        return candidates.get(random.nextInt(candidates.size()));
    }

    private Action attackAction(GameDefinition definition, GameState state) {
        UnitState attacker = state.units().stream()
                .filter(unit -> unit.playerId() == state.activePlayerId())
                .filter(unit -> "ready".equals(unit.status()) && unit.hullPoints() > 0)
                .filter(unit -> unitCanActInPhase(definition, state.phaseCode(), unit))
                 .filter(unit -> state.units().stream().anyMatch(target -> canAttack(definition, state, unit, target)))
                .findFirst().orElse(null);
        if (attacker == null) {
            return null;
        }
        UnitState target = state.units().stream()
                .filter(candidate -> canAttack(definition, state, attacker, candidate))
                .findFirst().orElse(null);
        return target == null ? null : new Action("attack_unit", attacker.playerId(), Map.of(
                "unitId", attacker.unitId(), "targetUnitId", target.unitId()));
    }

    static double deploymentX(BoardState board, int playerIndex) {
        return board.width() * (playerIndex % 2 == 0 ? DEPLOYMENT_EDGE : 1 - DEPLOYMENT_EDGE);
    }

    static double deploymentX(BoardState board, int playerIndex, int unitIndex) {
        double edge = deploymentX(board, playerIndex);
        double depth = Math.min(unitIndex / DEPLOYMENT_ROWS, 3) * DEPLOYMENT_DEPTH_STEP;
        return playerIndex % 2 == 0 ? edge + depth : edge - depth;
    }

    static double deploymentY(BoardState board, int unitIndex) {
        int row = unitIndex % DEPLOYMENT_ROWS;
        return board.height() * (DEPLOYMENT_ROW_MARGIN + row * DEPLOYMENT_ROW_STEP);
    }

    private Action moveAction(GameDefinition definition, GameState state, UnitState unit) {
        UnitDefinition profile = profile(definition, unit.unitCode());
        if (profile == null) {
            return null;
        }
        int speed = unit.currentSpeed() > 0 ? unit.currentSpeed() : Math.min(1, profile.maximumSpeed());
        ManeuverProfile maneuver = profile.maneuverProfile(speed);
        if (maneuver == null || speed <= 0) return null;
        UnitState target = state.units().stream()
                .filter(candidate -> candidate.playerId() != unit.playerId() && candidate.hullPoints() > 0)
                .min(Comparator.comparingDouble(candidate -> distance(unit, candidate))).orElse(null);
        if (target == null) {
            return null;
        }
        double distance = distance(unit, target);
        if (distance == 0) {
            return null;
        }
        double targetY = approachY(state.board(), target, unit.unitId());
        double desiredFacing = movementFacing(unit.positionX(), unit.positionY(), target.positionX(), targetY);
        double remainingTurn = normalizeDegrees(desiredFacing - unit.facing());
        if (remainingTurn > 180) remainingTurn -= 360;
        int direction = remainingTurn < 0 ? -1 : 1;
        List<Integer> yaw = new java.util.ArrayList<>();
        double remaining = remainingTurn;
        for (int index = 0; index < maneuver.maximumYawClicks().size(); index++) {
            int clicks = direction * Math.min(maneuver.maximumYawClicks().get(index),
                    (int) Math.round(Math.abs(remaining) / MovementCalculator.YAW_CLICK_DEGREES));
            yaw.add(clicks);
            remaining -= clicks * MovementCalculator.YAW_CLICK_DEGREES;
        }
        return new Action("move_unit", unit.playerId(), Map.of(
                "unitId", unit.unitId(), "speed", speed, "yaw", yaw));
    }

    static double approachY(BoardState board, UnitState target, long unitId) {
        int lane = (int) Math.floorMod(unitId, 5) - 2;
        return Math.max(board.height() * .10,
                Math.min(board.height() * .90, target.positionY() + lane * board.height() * .10));
    }

    static double movementFacing(double fromX, double fromY, double toX, double toY) {
        double degrees = Math.toDegrees(Math.atan2(toY - fromY, toX - fromX));
        return degrees < 0 ? degrees + 360 : degrees;
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    private boolean canAttack(GameDefinition definition, GameState state,
                              UnitState attacker, UnitState target) {
        if (target.playerId() == attacker.playerId() || target.hullPoints() <= 0) {
            return false;
        }
        return engine.canAttack(definition, state, attacker, target);
    }

    private boolean unitCanActInPhase(GameDefinition definition, String phase, UnitState unit) {
        UnitDefinition profile = profile(definition, unit.unitCode());
        if (profile == null) {
            return true;
        }
        boolean squadron = "squadron".equalsIgnoreCase(profile.classification());
        return "squadron".equals(phase) == squadron;
    }

    private void syncFleetLayout(long sessionId, Action action, GameState state) {
        long engineUnitId = ((Number) action.parameters().get("unitId")).longValue();
        String unitCode = (String) action.parameters().get("unitCode");
        long variantId;
        try {
            variantId = Long.parseLong(unitCode);
        } catch (NumberFormatException exception) {
            return;
        }
        FleetSummary fleet = battle.findFleets(sessionId).stream()
                .filter(item -> item.sessionPlayerId() == action.actorPlayerId())
                .findFirst().orElse(null);
        if (fleet == null) {
            return;
        }
        List<FleetUnit> existing = battle.findFleetUnits(fleet.fleetId());
        FleetUnit stored = existing.stream()
                .filter(item -> item.state() != null && item.state().contains("\"engineUnitId\":" + engineUnitId))
                .findFirst().orElse(null);
        UnitState unit = state.units().stream().filter(item -> item.unitId() == engineUnitId).findFirst().orElse(null);
        if (unit == null) {
            return;
        }
        String stateJson = writeUnitState(unit);
        String unitName = battle.findUnitVariantName(variantId).orElse(unitCode);
        if (stored == null) {
            battle.createUnit(fleet.fleetId(), variantId, unitName,
                    java.math.BigDecimal.valueOf(unit.positionX()), java.math.BigDecimal.valueOf(unit.positionY()),
                    java.math.BigDecimal.valueOf(unit.facing()), unit.status(), stateJson);
        } else {
            battle.updateUnit(stored.unitInstanceId(), unitName,
                    java.math.BigDecimal.valueOf(unit.positionX()), java.math.BigDecimal.valueOf(unit.positionY()),
                    java.math.BigDecimal.valueOf(unit.facing()), unit.status(), stateJson);
        }
    }

    private static double distance(UnitState first, UnitState second) {
        return Math.hypot(second.positionX() - first.positionX(), second.positionY() - first.positionY());
    }

    private static UnitDefinition profile(GameDefinition definition, String code) {
        return definition.unitDefinitions().stream().filter(unit -> unit.code().equals(code)).findFirst().orElse(null);
    }

    private static double numericOption(Map<String, Object> values, String name, double fallback) {
        Object value = values.get(name);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static int integerOption(Map<String, Object> values, String name, int fallback) {
        Object value = values.get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private GameState readState(String state) {
        try {
            return objectMapper.readValue(state, GameState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored CPU game state is invalid", exception);
        }
    }

    private String writeState(GameState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize CPU game state", exception);
        }
    }

    private String writeUnitState(UnitState unit) {
        try {
            return objectMapper.writeValueAsString(Map.of("engineUnitId", unit.unitId(), "unit", unit));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize CPU unit state", exception);
        }
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize CPU event payload", exception);
        }
    }
}
