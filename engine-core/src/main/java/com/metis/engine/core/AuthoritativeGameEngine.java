package com.metis.engine.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

/** Framework-independent setup boundary for the authoritative rules engine. */
public final class AuthoritativeGameEngine implements GameEngine {
    private final RandomSource random;

    public AuthoritativeGameEngine() {
        this(bound -> java.util.concurrent.ThreadLocalRandom.current().nextInt(bound));
    }

    public AuthoritativeGameEngine(RandomSource random) {
        this.random = java.util.Objects.requireNonNull(random, "random is required");
    }

    public SetupValidation validateSetup(GameDefinition definition, GameSetup setup) {
        List<SetupValidation.Violation> violations = new ArrayList<>();
        if (definition == null) {
            violations.add(new SetupValidation.Violation("DEFINITION_REQUIRED", "A game definition is required"));
        }
        if (setup == null) {
            violations.add(new SetupValidation.Violation("SETUP_REQUIRED", "A game setup is required"));
        }
        if (!violations.isEmpty()) {
            return new SetupValidation(violations);
        }

        if (setup.playerCount() < definition.minimumPlayers()
                || setup.playerCount() > definition.maximumPlayers()) {
            violations.add(new SetupValidation.Violation("PLAYER_COUNT_INVALID",
                    "Player count must be between " + definition.minimumPlayers() + " and "
                            + definition.maximumPlayers()));
        }
        if (setup.cpuCount() < definition.minimumCpu() || setup.cpuCount() > definition.maximumCpu()) {
            violations.add(new SetupValidation.Violation("CPU_COUNT_INVALID",
                    "CPU count must be between " + definition.minimumCpu() + " and " + definition.maximumCpu()));
        }
        if (setup.cpuCount() > setup.playerCount()) {
            violations.add(new SetupValidation.Violation("CPU_COUNT_EXCEEDS_PLAYERS",
                    "CPU count cannot exceed player count"));
        }
        if (setup.players().size() != setup.playerCount()) {
            violations.add(new SetupValidation.Violation("PLAYER_SEATS_INCOMPLETE",
                    "The setup must provide one player for every seat"));
        }
        long actualCpuCount = setup.players().stream().filter(player -> "cpu".equals(player.kind())).count();
        if (actualCpuCount != setup.cpuCount()) {
            violations.add(new SetupValidation.Violation("CPU_SEATS_MISMATCH",
                    "CPU count does not match the configured player seats"));
        }
        Set<Integer> seats = new HashSet<>();
        Set<String> factions = new HashSet<>();
        for (Player player : setup.players()) {
            if (!seats.add(player.seatNumber())) {
                violations.add(new SetupValidation.Violation("DUPLICATE_SEAT",
                        "Seat " + player.seatNumber() + " is assigned more than once"));
            }
            if (!definition.factionCodes().isEmpty()) {
                if (player.factionCode() == null || !definition.factionCodes().contains(player.factionCode())) {
                    violations.add(new SetupValidation.Violation("FACTION_INVALID",
                            "Faction is not available in this game definition: " + player.factionCode()));
                } else if (!factions.add(player.factionCode())) {
                    violations.add(new SetupValidation.Violation("DUPLICATE_FACTION",
                            "Faction is assigned to more than one player: " + player.factionCode()));
                }
            }
        }
        if (setup.board() == null) {
            violations.add(new SetupValidation.Violation("BOARD_REQUIRED", "A board is required"));
        }
        return new SetupValidation(violations);
    }

    public GameState createInitialState(long sessionId, GameDefinition definition, GameSetup setup) {
        SetupValidation validation = validateSetup(definition, setup);
        if (!validation.valid()) {
            throw new InvalidSetupException(validation);
        }
        return new GameState(sessionId, definition.definitionVersionId(), "setup", 0, "setup", null, 0,
                setup.players(), List.of(), setup.board(), List.of(), setup.options());
    }

    public List<LegalAction> legalActions(GameState state) {
        return legalActions(null, state);
    }

    @Override
    public List<LegalAction> legalActions(GameDefinition definition, GameState state) {
        if (state == null) {
            return List.of();
        }
        if ("setup".equals(state.status())) {
            return List.of(new LegalAction("start_game", 0, "Start the game", Map.of()));
        }
        if (!"running".equals(state.status())) {
            return List.of();
        }
        if ("deployment".equals(state.phaseCode())) {
            List<LegalAction> actions = new ArrayList<>();
            for (Player player : state.players()) {
                actions.add(new LegalAction("deploy_unit", player.playerId(),
                        "Deploy a unit for " + player.factionCode(),
                        Map.of("unitId", "number", "unitCode", "string", "positionX", "number",
                                "positionY", "number", "facing", "number")));
            }
            if (!state.units().isEmpty()) {
                actions.add(new LegalAction("advance_phase", 0, "Advance to command", Map.of()));
            }
            return actions;
        }
        if ("command".equals(state.phaseCode()) || "status".equals(state.phaseCode())) {
            return List.of(new LegalAction("advance_phase", 0,
                    "Advance from " + state.phaseCode(), Map.of()));
        }
        if (("ship".equals(state.phaseCode()) || "squadron".equals(state.phaseCode()))
                && state.activePlayerId() != null) {
            List<LegalAction> actions = new ArrayList<>();
            state.units().stream()
                    .filter(unit -> unit.playerId() == state.activePlayerId() && "ready".equals(unit.status())
                            && unit.hullPoints() > 0 && unitCanActInPhase(definition, state.phaseCode(), unit))
                    .forEach(unit -> {
                        actions.add(new LegalAction("move_unit", state.activePlayerId(),
                                 "Move unit " + unit.unitCode(),
                                 Map.of("unitId", "number", "speed", "number", "yaw", "array")));
                        state.units().stream()
                        .filter(target -> target.playerId() != unit.playerId() && target.hullPoints() > 0)
                        .filter(target -> attackPlan(definition, state, unit, target) != null)
                        .forEach(target -> actions.add(new LegalAction("attack_unit", state.activePlayerId(),
                                        "Attack unit " + target.unitId(),
                                        Map.of("unitId", "number", "targetUnitId", "number"))));
                    });
            actions.add(new LegalAction("end_turn", state.activePlayerId(), "End the active player's turn", Map.of()));
            return actions;
        }
        return List.of();
    }

    public boolean canAttack(GameDefinition definition, GameState state,
                             UnitState attacker, UnitState target) {
        if (attacker == null || target == null || attacker.playerId() == target.playerId()
                || target.hullPoints() <= 0) {
            return false;
        }
        return attackPlan(definition, state, attacker, target) != null;
    }

    public ActionResult applyAction(GameState state, Action action) {
        return applyAction(null, state, action);
    }

    @Override
    public ActionResult applyAction(GameDefinition definition, GameState state, Action action) {
        if (state == null) {
            return rejected("STATE_REQUIRED", "A game state is required");
        }
        if (action == null || action.type() == null || action.type().isBlank()) {
            return rejected("ACTION_REQUIRED", "An action type is required");
        }
        return switch (action.type()) {
            case "start_game" -> startGame(state);
            case "advance_phase" -> advancePhase(state);
            case "deploy_unit" -> deployUnit(definition, state, action);
            case "move_unit" -> moveUnit(definition, state, action);
            case "attack_unit" -> attackUnit(definition, state, action);
            case "end_turn" -> endTurn(state, action);
            default -> rejected("ACTION_UNSUPPORTED", "Action type is not supported: " + action.type());
        };
    }

    private ActionResult startGame(GameState state) {
        if (!"setup".equals(state.status())) {
            return rejected("GAME_ALREADY_STARTED", "The game is not in setup");
        }
        GameEvent event = event(state, "game_started", null, Map.of("phase", "deployment"));
        return accepted(new GameState(state.sessionId(), state.definitionVersionId(), "running", 1,
                "deployment", null, state.stateVersion() + 1, state.players(), state.units(), state.board(),
                append(state.events(), event), state.values()), event);
    }

    private ActionResult advancePhase(GameState state) {
        if (!"running".equals(state.status())) {
            return rejected("PHASE_ADVANCE_NOT_ALLOWED", "The game is not running");
        }
        if ("deployment".equals(state.phaseCode())) {
            if (state.units().isEmpty()) {
                return rejected("UNITS_REQUIRED", "At least one unit must be deployed before command");
            }
            return transition(state, "command", null);
        }
        if ("command".equals(state.phaseCode())) {
            return transition(state, "ship", firstPlayer(state));
        }
        if ("status".equals(state.phaseCode())) {
            boolean unlimitedRounds = Boolean.TRUE.equals(state.values().get("unlimitedRounds"));
            int maximumRounds = numericOption(state.values(), "maximumRounds", 6);
            boolean roundLimitReached = !unlimitedRounds && state.round() >= maximumRounds;
            Map<String, Object> values = new HashMap<>(state.values());
            if (roundLimitReached) {
                values.put("outcome", "round_limit_draw");
            }
            List<UnitState> resetUnits = state.units().stream()
                    .map(unit -> unit.hullPoints() > 0
                        ? new UnitState(unit.unitId(), unit.playerId(), unit.unitCode(), unit.positionX(),
                             unit.positionY(), unit.facing(), "ready", unit.hullPoints(), unit.shieldPoints(),
                             readyDefenseTokenCount(unit), unit.currentSpeed(), unit.shieldZones(),
                             readiedDefenseTokens(unit), Map.of())
                            : unit)
                    .toList();
            String nextStatus = roundLimitReached ? "finished" : "running";
            String nextPhase = roundLimitReached ? "victory" : "command";
            int nextRound = roundLimitReached ? state.round() : state.round() + 1;
            Long nextActivePlayer = null;
            String eventType = roundLimitReached ? "round_limit_reached" : "round_started";
            Map<String, Object> payload = roundLimitReached
                    ? Map.of("round", state.round(), "maximumRounds", maximumRounds, "outcome", "draw")
                    : Map.of("round", nextRound, "phase", "command");
            GameEvent event = event(state, eventType, null, payload);
            return accepted(new GameState(state.sessionId(), state.definitionVersionId(), nextStatus, nextRound,
                    nextPhase, nextActivePlayer, state.stateVersion() + 1, state.players(), resetUnits, state.board(),
                    append(state.events(), event), values), event);
        }
        return rejected("PHASE_ADVANCE_NOT_ALLOWED", "The current phase advances through player turns");
    }

    private ActionResult transition(GameState state, String nextPhase, Long activePlayerId) {
        return transition(state, nextPhase, activePlayerId, null);
    }

    private ActionResult transition(GameState state, String nextPhase, Long activePlayerId, Long actorPlayerId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("from", state.phaseCode());
        payload.put("to", nextPhase);
        if (activePlayerId != null) {
            payload.put("activePlayerId", activePlayerId);
        }
        GameEvent event = event(state, "phase_changed", actorPlayerId, payload);
        return accepted(new GameState(state.sessionId(), state.definitionVersionId(), state.status(), state.round(),
                nextPhase, activePlayerId, state.stateVersion() + 1, state.players(), state.units(), state.board(),
                append(state.events(), event), state.values()), event);
    }

    private static long firstPlayer(GameState state) {
        return state.players().stream()
                .min(java.util.Comparator.comparingInt(Player::seatNumber))
                .orElseThrow().playerId();
    }

    private static boolean unitCanActInPhase(GameDefinition definition, String phase, UnitState unit) {
        if (definition == null) {
            return true;
        }
        UnitDefinition profile = unitDefinition(definition, unit.unitCode());
        if (profile == null) {
            return true;
        }
        boolean squadron = "squadron".equalsIgnoreCase(profile.classification());
        return "squadron".equals(phase) == squadron;
    }

    private ActionResult deployUnit(GameDefinition definition, GameState state, Action action) {
        if (!"running".equals(state.status()) || !"deployment".equals(state.phaseCode())) {
            return rejected("DEPLOYMENT_NOT_ALLOWED", "Units can only be deployed during deployment");
        }
        Player player = player(state, action.actorPlayerId());
        if (player == null) {
            return rejected("PLAYER_NOT_FOUND", "The acting player is not part of the game");
        }
        long unitId = longParameter(action.parameters(), "unitId");
        if (state.units().stream().anyMatch(unit -> unit.unitId() == unitId)) {
            return rejected("UNIT_ALREADY_DEPLOYED", "Unit " + unitId + " is already deployed");
        }
        String unitCode = stringParameter(action.parameters(), "unitCode");
        UnitDefinition unitDefinition = unitDefinition(definition, unitCode);
        if (definition != null && !definition.unitDefinitions().isEmpty() && unitDefinition == null) {
            return rejected("UNIT_DEFINITION_NOT_FOUND", "Unit is not available in this game definition: " + unitCode);
        }
        if (unitDefinition != null && !unitDefinition.factionCode().equals(player.factionCode())) {
            return rejected("UNIT_FACTION_MISMATCH", "The unit does not belong to the acting player's faction");
        }
        double x = doubleParameter(action.parameters(), "positionX");
        double y = doubleParameter(action.parameters(), "positionY");
        double facing = doubleParameter(action.parameters(), "facing");
        if (!onBoard(state.board(), x, y)) {
            return rejected("POSITION_OUT_OF_BOUNDS", "The unit must be placed inside the board");
        }
        int hullPoints = unitDefinition == null ? 1 : unitDefinition.maximumHullPoints();
        int shieldPoints = unitDefinition == null ? 0 : unitDefinition.maximumShieldPoints();
        int defenseTokens = unitDefinition == null ? 0 : unitDefinition.defenseTokenCount();
        int currentSpeed = unitDefinition == null ? 0 : defaultSpeed(unitDefinition);
        UnitState unit = new UnitState(unitId, player.playerId(), unitCode, x, y, facing, "ready",
                hullPoints, shieldPoints, defenseTokens, currentSpeed,
                unitDefinition == null ? Map.of() : unitDefinition.shieldZones(),
                unitDefinition == null ? genericDefenseTokens(defenseTokens) : unitDefinition.defenseTokenCounts(),
                Map.of());
        GameEvent event = event(state, "unit_deployed", action.actorPlayerId(),
                Map.of("unitId", unitId, "positionX", x, "positionY", y));
        List<UnitState> units = new ArrayList<>(state.units());
        units.add(unit);
        return accepted(new GameState(state.sessionId(), state.definitionVersionId(), state.status(), state.round(),
                state.phaseCode(), state.activePlayerId(), state.stateVersion() + 1, state.players(), units, state.board(),
                append(state.events(), event), state.values()), event);
    }

    private ActionResult moveUnit(GameDefinition definition, GameState state, Action action) {
        if (!"running".equals(state.status())
                || (!"ship".equals(state.phaseCode()) && !"squadron".equals(state.phaseCode()))) {
            return rejected("MOVEMENT_NOT_ALLOWED", "Units can only move during the ship or squadron phase");
        }
        Player player = player(state, action.actorPlayerId());
        if (player == null) {
            return rejected("PLAYER_NOT_FOUND", "The acting player is not part of the game");
        }
        long unitId = longParameter(action.parameters(), "unitId");
        UnitState unit = state.units().stream().filter(item -> item.unitId() == unitId).findFirst().orElse(null);
        if (unit == null) {
            return rejected("UNIT_NOT_FOUND", "Unit " + unitId + " is not deployed");
        }
        if (unit.playerId() != player.playerId()) {
            return rejected("UNIT_NOT_OWNED", "The acting player does not control unit " + unitId);
        }
        if (!unitCanActInPhase(definition, state.phaseCode(), unit)) {
            return rejected("UNIT_PHASE_MISMATCH", "This unit cannot activate during the " + state.phaseCode() + " phase");
        }
        if (!"ready".equals(unit.status())) {
            return rejected("UNIT_ALREADY_ACTIVATED", "Unit " + unitId + " has already acted this turn");
        }
        if (state.activePlayerId() == null || state.activePlayerId() != player.playerId()) {
            return rejected("NOT_ACTIVE_PLAYER", "It is not the acting player's turn");
        }
        if (action.parameters().containsKey("speed") || action.parameters().containsKey("yaw")) {
            return moveByManeuver(definition, state, action, unit);
        }
        double x = doubleParameter(action.parameters(), "positionX");
        double y = doubleParameter(action.parameters(), "positionY");
        double distance = Math.hypot(x - unit.positionX(), y - unit.positionY());
        UnitDefinition unitDefinition = unitDefinition(definition, unit.unitCode());
        double maximumDistance = numericOption(state.values(), "maximumMovementDistance", 0);
        if (maximumDistance <= 0 && unitDefinition != null) {
            maximumDistance = unitDefinition.maximumMovementDistance();
        }
        if (distance > maximumDistance) {
            return rejected("MOVEMENT_DISTANCE_EXCEEDED", "The requested movement exceeds the unit movement limit");
        }
        if (!onBoard(state.board(), x, y)) {
            return rejected("POSITION_OUT_OF_BOUNDS", "The unit must remain inside the board");
        }
        double facing = doubleParameter(action.parameters(), "facing");
        UnitState moved = new UnitState(unit.unitId(), unit.playerId(), unit.unitCode(), x, y, facing,
                "activated", unit.hullPoints(), unit.shieldPoints(), unit.defenseTokens(), unit.currentSpeed(),
                unit.shieldZones(), unit.readyDefenseTokens(), unit.exhaustedDefenseTokens());
        List<UnitState> units = new ArrayList<>(state.units());
        units.set(units.indexOf(unit), moved);
        GameEvent event = event(state, "unit_moved", action.actorPlayerId(),
                Map.of("unitId", unitId, "positionX", x, "positionY", y));
        return accepted(new GameState(state.sessionId(), state.definitionVersionId(), state.status(), state.round(),
                state.phaseCode(), state.activePlayerId(), state.stateVersion() + 1, state.players(), units, state.board(),
                append(state.events(), event), state.values()), event);
    }

    private ActionResult moveByManeuver(GameDefinition definition, GameState state, Action action, UnitState unit) {
        UnitDefinition profile = unitDefinition(definition, unit.unitCode());
        if (profile == null) {
            return rejected("UNIT_DEFINITION_NOT_FOUND", "A maneuver profile is required for unit " + unit.unitCode());
        }
        int speed = integerParameter(action.parameters(), "speed");
        ManeuverProfile maneuver = profile.maneuverProfile(speed);
        if (maneuver == null) {
            return rejected("SPEED_INVALID", "Speed " + speed + " is not available to unit " + unit.unitCode());
        }
        List<Integer> yaw = integerListParameter(action.parameters(), "yaw");
        if (yaw.size() != maneuver.maximumYawClicks().size()
                || java.util.stream.IntStream.range(0, yaw.size())
                .anyMatch(index -> Math.abs(yaw.get(index)) > maneuver.maximumYawClicks().get(index))) {
            return rejected("MANEUVER_INVALID", "The selected yaw exceeds the unit's maneuver chart");
        }
        double distancePerSpeed = numericOption(state.values(), "distancePerSpeed",
                numericOption(state.values(), "maximumMovementDistance", 100));
        if (!(distancePerSpeed > 0)) {
            return rejected("MOVEMENT_SCALE_INVALID", "A positive board distance per speed is required");
        }
        List<MovementCalculator.Point> path = MovementCalculator.path(unit, speed, yaw, distancePerSpeed);
        for (MovementCalculator.Point point : path) {
            if (!onBoard(state.board(), point.x(), point.y(), profile.baseRadius())) {
                return rejected("POSITION_OUT_OF_BOUNDS", "The unit base must remain inside the board");
            }
        }
        for (UnitState other : state.units()) {
            if (other.unitId() == unit.unitId() || other.hullPoints() <= 0) continue;
            UnitDefinition otherProfile = unitDefinition(definition, other.unitCode());
            double otherRadius = otherProfile == null ? 0 : otherProfile.baseRadius();
            if (path.stream().skip(1).anyMatch(point -> Math.hypot(point.x() - other.positionX(),
                    point.y() - other.positionY()) < profile.baseRadius() + otherRadius)) {
                return rejected("MOVEMENT_COLLISION", "The unit base would overlap another unit");
            }
        }
        double facing = MovementCalculator.finalFacing(unit.facing(), yaw);
        MovementCalculator.Pose pose = MovementCalculator.finalPose(path, facing);
        UnitState moved = new UnitState(unit.unitId(), unit.playerId(), unit.unitCode(), pose.x(), pose.y(),
                pose.facing(), "activated", unit.hullPoints(), unit.shieldPoints(), unit.defenseTokens(), speed,
                unit.shieldZones(), unit.readyDefenseTokens(), unit.exhaustedDefenseTokens());
        List<UnitState> units = new ArrayList<>(state.units());
        units.set(units.indexOf(unit), moved);
        GameEvent event = event(state, "unit_moved", action.actorPlayerId(), Map.of(
                "unitId", unit.unitId(), "positionX", pose.x(), "positionY", pose.y(),
                "facing", pose.facing(), "speed", speed, "yaw", yaw));
        return accepted(new GameState(state.sessionId(), state.definitionVersionId(), state.status(), state.round(),
                state.phaseCode(), state.activePlayerId(), state.stateVersion() + 1, state.players(), units,
                state.board(), append(state.events(), event), state.values()), event);
    }

    private ActionResult attackUnit(GameDefinition definition, GameState state, Action action) {
        if (!"running".equals(state.status())
                || (!"ship".equals(state.phaseCode()) && !"squadron".equals(state.phaseCode()))) {
            return rejected("ATTACK_NOT_ALLOWED", "Attacks can only be made during the ship or squadron phase");
        }
        Player player = player(state, action.actorPlayerId());
        if (player == null) {
            return rejected("PLAYER_NOT_FOUND", "The acting player is not part of the game");
        }
        UnitState attacker = state.units().stream()
                .filter(unit -> unit.unitId() == longParameter(action.parameters(), "unitId"))
                .findFirst().orElse(null);
        if (attacker == null) {
            return rejected("UNIT_NOT_FOUND", "The attacking unit is not deployed");
        }
        if (attacker.playerId() != player.playerId()) {
            return rejected("UNIT_NOT_OWNED", "The acting player does not control the attacking unit");
        }
        if (!unitCanActInPhase(definition, state.phaseCode(), attacker)) {
            return rejected("UNIT_PHASE_MISMATCH", "This unit cannot activate during the " + state.phaseCode() + " phase");
        }
        if (!"ready".equals(attacker.status())) {
            return rejected("UNIT_ALREADY_ACTIVATED", "Unit " + attacker.unitId() + " has already acted this turn");
        }
        UnitState target = state.units().stream()
                .filter(unit -> unit.unitId() == longParameter(action.parameters(), "targetUnitId"))
                .findFirst().orElse(null);
        if (target == null) {
            return rejected("TARGET_NOT_FOUND", "The target unit is not deployed");
        }
        if (target.playerId() == player.playerId()) {
            return rejected("TARGET_NOT_HOSTILE", "A unit can only attack an opposing unit");
        }
        if (target.hullPoints() == 0) {
            return rejected("TARGET_DESTROYED", "The target unit has already been destroyed");
        }
        AttackPlan attackPlan = attackPlan(definition, state, attacker, target);
        if (attackPlan == null) {
            return rejected("ATTACK_OUT_OF_RANGE", "The target is outside the attacking unit's range");
        }
        UnitDefinition attackerDefinition = unitDefinition(definition, attacker.unitCode());
        int attackDice = attackPlan.dice().stream().mapToInt(AttackDie::count).sum();
        Map<String, Integer> diceByColor = new HashMap<>();
        attackPlan.dice().forEach(die -> diceByColor.merge(die.color(), die.count(), Integer::sum));
        int hits = 0;
        for (AttackDie die : attackPlan.dice()) {
            for (int index = 0; index < die.count(); index++) {
                if (random.nextInt(6) >= 3) {
                    hits++;
                }
            }
        }
        Map<String, Object> values = new HashMap<>(state.values());
        int noDamageAttacks = numericOption(state.values(), "noDamageAttacks", 0);
        boolean stabilizedRoll = hits == 0 && ++noDamageAttacks >= 3;
        if (stabilizedRoll) {
            hits = 1;
            noDamageAttacks = 0;
        } else if (hits > 0) {
            noDamageAttacks = 0;
        }
        values.put("noDamageAttacks", noDamageAttacks);
        int rolledDamage = hits * (attackPlan.generic() && attackerDefinition != null
                ? attackerDefinition.attackDamage() : 1);
        String defenseTokenType = hits > 0 ? selectedDefenseToken(action, target) : null;
        if (hits > 0 && action.parameters().containsKey("defenseTokenType") && defenseTokenType == null) {
            return rejected("DEFENSE_TOKEN_NOT_READY", "The selected defense token is not ready");
        }
        boolean defenseTokenSpent = defenseTokenType != null;
        int mitigatedDamage = Math.max(0, rolledDamage - (defenseTokenSpent ? 1 : 0));
        String targetZone = targetZone(target, attacker);
        Map<String, Integer> remainingShieldZones = new HashMap<>(target.shieldZones());
        int availableShields = remainingShieldZones.isEmpty()
                ? target.shieldPoints() : remainingShieldZones.getOrDefault(targetZone, 0);
        int shieldDamage = Math.min(availableShields, mitigatedDamage);
        int hullDamage = mitigatedDamage - shieldDamage;
        if (!remainingShieldZones.isEmpty()) {
            remainingShieldZones.put(targetZone, availableShields - shieldDamage);
        }
        int remainingShields = remainingShieldZones.isEmpty()
                ? target.shieldPoints() - shieldDamage
                : remainingShieldZones.values().stream().mapToInt(Integer::intValue).sum();
        int remainingHull = Math.max(0, target.hullPoints() - hullDamage);
        Map<String, Integer> remainingReadyTokens = defenseTokenSpent
                ? decrementToken(target.readyDefenseTokens(), defenseTokenType) : target.readyDefenseTokens();
        Map<String, Integer> remainingExhaustedTokens = defenseTokenSpent
                ? incrementToken(target.exhaustedDefenseTokens(), defenseTokenType) : target.exhaustedDefenseTokens();
        int remainingDefenseTokens = remainingReadyTokens.values().stream().mapToInt(Integer::intValue).sum();
        String targetStatus = remainingHull == 0 ? "destroyed" : target.status();
        UnitState damaged = new UnitState(target.unitId(), target.playerId(), target.unitCode(), target.positionX(),
                  target.positionY(), target.facing(), targetStatus, remainingHull, remainingShields,
                  remainingDefenseTokens, target.currentSpeed(), remainingShieldZones,
                  remainingReadyTokens, remainingExhaustedTokens);
        UnitState activated = new UnitState(attacker.unitId(), attacker.playerId(), attacker.unitCode(),
                 attacker.positionX(), attacker.positionY(), attacker.facing(), "activated", attacker.hullPoints(),
                 attacker.shieldPoints(), attacker.defenseTokens(), attacker.currentSpeed(), attacker.shieldZones(),
                 attacker.readyDefenseTokens(), attacker.exhaustedDefenseTokens());
        List<UnitState> units = new ArrayList<>(state.units());
        units.set(units.indexOf(attacker), activated);
        units.set(units.indexOf(target), damaged);
        Set<Long> survivingPlayers = units.stream()
                .filter(unit -> unit.hullPoints() > 0)
                .map(UnitState::playerId)
                .collect(java.util.stream.Collectors.toSet());
        boolean gameWon = survivingPlayers.size() == 1;
        Long winnerPlayerId = gameWon ? survivingPlayers.iterator().next() : null;
        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("unitId", attacker.unitId());
        eventPayload.put("targetUnitId", target.unitId());
        eventPayload.put("attackDice", attackDice);
        eventPayload.put("hits", hits);
        eventPayload.put("damage", hullDamage);
        eventPayload.put("shieldDamage", shieldDamage);
        eventPayload.put("defenseTokenSpent", defenseTokenSpent);
        if (defenseTokenType != null) eventPayload.put("defenseTokenType", defenseTokenType);
        eventPayload.put("stabilizedRoll", stabilizedRoll);
        eventPayload.put("remainingHullPoints", remainingHull);
        eventPayload.put("remainingShieldPoints", remainingShields);
        eventPayload.put("arc", attackPlan.arc());
        eventPayload.put("targetZone", targetZone);
        eventPayload.put("rangeBand", attackPlan.rangeBand());
        eventPayload.put("diceByColor", diceByColor);
        String nextStatus = state.status();
        String nextPhase = state.phaseCode();
        Long nextActivePlayerId = state.activePlayerId();
        String eventType = "unit_attacked";
        if (gameWon) {
            nextStatus = "finished";
            nextPhase = "victory";
            nextActivePlayerId = null;
            values.put("winnerPlayerId", winnerPlayerId);
            values.put("outcome", "victory");
            eventType = "game_won";
            eventPayload.put("winnerPlayerId", winnerPlayerId);
        }
        GameEvent event = event(state, eventType, action.actorPlayerId(),
                eventPayload);
        return accepted(new GameState(state.sessionId(), state.definitionVersionId(), nextStatus, state.round(),
                nextPhase, nextActivePlayerId, state.stateVersion() + 1, state.players(), units, state.board(),
                append(state.events(), event), values), event);
    }

    private ActionResult endTurn(GameState state, Action action) {
        if (!"running".equals(state.status())
                || (!"ship".equals(state.phaseCode()) && !"squadron".equals(state.phaseCode()))) {
            return rejected("TURN_END_NOT_ALLOWED", "Turns can only end during the ship or squadron phase");
        }
        if (state.activePlayerId() == null || state.activePlayerId() != action.actorPlayerId()) {
            return rejected("NOT_ACTIVE_PLAYER", "Only the active player can end the turn");
        }
        List<Player> orderedPlayers = state.players().stream()
                .sorted(java.util.Comparator.comparingInt(Player::seatNumber)).toList();
        int currentIndex = 0;
        for (int index = 0; index < orderedPlayers.size(); index++) {
            if (orderedPlayers.get(index).playerId() == action.actorPlayerId()) {
                currentIndex = index;
                break;
            }
        }
        int nextIndex = (currentIndex + 1) % orderedPlayers.size();
        if (nextIndex != 0) {
            long nextPlayerId = orderedPlayers.get(nextIndex).playerId();
            GameEvent event = event(state, "turn_started", action.actorPlayerId(),
                    Map.of("activePlayerId", nextPlayerId, "round", state.round(),
                            "phase", state.phaseCode()));
            return accepted(new GameState(state.sessionId(), state.definitionVersionId(), state.status(), state.round(),
                    state.phaseCode(), nextPlayerId, state.stateVersion() + 1, state.players(), state.units(),
                    state.board(), append(state.events(), event), state.values()), event);
        }
        String nextPhase = "ship".equals(state.phaseCode()) ? "squadron" : "status";
        Long nextPlayerId = "squadron".equals(state.phaseCode()) ? null : firstPlayer(state);
        return transition(state, nextPhase, nextPlayerId, action.actorPlayerId());
    }

    private static int numericOption(Map<String, Object> values, String name, int fallback) {
        Object value = values.get(name);
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
    }

    private static String selectedDefenseToken(Action action, UnitState target) {
        if (target.readyDefenseTokens().isEmpty()) return null;
        Object requested = action.parameters().get("defenseTokenType");
        if (requested != null) {
            if (!(requested instanceof String type) || type.isBlank()) return null;
            return target.readyDefenseTokens().keySet().stream()
                    .filter(key -> key.equalsIgnoreCase(type.trim()))
                    .filter(key -> target.readyDefenseTokens().getOrDefault(key, 0) > 0)
                    .findFirst().orElse(null);
        }
        return target.readyDefenseTokens().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private static Map<String, Integer> decrementToken(Map<String, Integer> tokens, String type) {
        Map<String, Integer> result = new HashMap<>(tokens);
        int remaining = result.getOrDefault(type, 0) - 1;
        if (remaining > 0) result.put(type, remaining); else result.remove(type);
        return result;
    }

    private static Map<String, Integer> incrementToken(Map<String, Integer> tokens, String type) {
        Map<String, Integer> result = new HashMap<>(tokens);
        result.merge(type, 1, Integer::sum);
        return result;
    }

    private static Map<String, Integer> readiedDefenseTokens(UnitState unit) {
        Map<String, Integer> result = new HashMap<>(unit.readyDefenseTokens());
        unit.exhaustedDefenseTokens().forEach((type, count) -> result.merge(type, count, Integer::sum));
        return result;
    }

    private static int readyDefenseTokenCount(UnitState unit) {
        return readiedDefenseTokens(unit).values().stream().mapToInt(Integer::intValue).sum();
    }

    private static Map<String, Integer> genericDefenseTokens(int count) {
        return count > 0 ? Map.of("generic", count) : Map.of();
    }

    private static ActionResult accepted(GameState state, GameEvent event) {
        return new ActionResult(true, "ACTION_ACCEPTED", event.type(), state);
    }

    private static ActionResult rejected(String code, String message) {
        return new ActionResult(false, code, message, null);
    }

    private static GameEvent event(GameState state, String type, Long actorPlayerId, Map<String, Object> payload) {
        return new GameEvent(state.events().size() + 1L, type, actorPlayerId, payload);
    }

    private static List<GameEvent> append(List<GameEvent> events, GameEvent event) {
        List<GameEvent> result = new ArrayList<>(events);
        result.add(event);
        return result;
    }

    private static Player player(GameState state, long playerId) {
        return state.players().stream().filter(item -> item.playerId() == playerId).findFirst().orElse(null);
    }

    private static boolean onBoard(BoardState board, double x, double y) {
        return onBoard(board, x, y, 0);
    }

    private static boolean onBoard(BoardState board, double x, double y, double radius) {
        return board != null && x >= radius && y >= radius
                && x <= board.width() - radius && y <= board.height() - radius;
    }

    private static long longParameter(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException(name + " must be numeric");
    }

    private static double doubleParameter(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException(name + " must be numeric");
    }

    private static int integerParameter(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(name + " must be numeric");
    }

    private static List<Integer> integerListParameter(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof List<?> values) {
            return values.stream().map(item -> {
                if (item instanceof Number number) return number.intValue();
                throw new IllegalArgumentException(name + " must contain only numbers");
            }).toList();
        }
        throw new IllegalArgumentException(name + " must be an array");
    }

    private static String stringParameter(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException(name + " is required");
    }

    private static double numericOption(Map<String, Object> values, String name, double fallback) {
        Object value = values.get(name);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static UnitDefinition unitDefinition(GameDefinition definition, String code) {
        if (definition == null) {
            return null;
        }
        return definition.unitDefinitions().stream()
                .filter(unit -> unit.code().equals(code))
                .findFirst().orElse(null);
    }

    private static int defaultSpeed(UnitDefinition definition) {
        return Math.min(1, definition.maximumSpeed());
    }

    private static AttackPlan attackPlan(GameDefinition definition, GameState state,
                                         UnitState attacker, UnitState target) {
        UnitDefinition attackerProfile = unitDefinition(definition, attacker.unitCode());
        UnitDefinition targetProfile = unitDefinition(definition, target.unitCode());
        if (definition != null && !definition.unitDefinitions().isEmpty()
                && (attackerProfile == null || targetProfile == null)) {
            return null;
        }
        String arc = firingArc(attacker, target);
        boolean targetSquadron = targetProfile != null
                && "squadron".equalsIgnoreCase(targetProfile.classification());
        boolean attackerSquadron = attackerProfile != null
                && "squadron".equalsIgnoreCase(attackerProfile.classification());
        boolean genericProfile = attackerProfile != null && attackerProfile.batteryArcs().values().stream()
                .flatMap(List::stream).anyMatch(die -> "generic".equalsIgnoreCase(die.color()));
        List<AttackDie> profileDice;
        if (attackerProfile == null) {
            profileDice = List.of(new AttackDie("generic", 1));
        } else if (targetSquadron) {
            profileDice = attackerProfile.antiSquadronDice();
        } else if (genericProfile) {
            profileDice = attackerProfile.batteryArcs().getOrDefault("front", List.of());
        } else if (attackerSquadron) {
            profileDice = attackerProfile.batteryArcs().getOrDefault("front", List.of());
        } else {
            profileDice = attackerProfile.batteryArcs().getOrDefault(arc, List.of());
        }
        if (profileDice.isEmpty()) return null;
        double distance = Math.hypot(target.positionX() - attacker.positionX(),
                target.positionY() - attacker.positionY());
        double maximumRange = attackerProfile == null
                ? numericOption(state.values(), "maximumAttackRange", 100)
                : attackerProfile.maximumAttackRange();
        String rangeBand = rangeBand(distance, state);
        if (rangeBand == null || distance > maximumRange) return null;
        List<AttackDie> dice = profileDice.stream()
                .map(die -> new AttackDie(die.color(), eligibleDice(die.color(), die.count(), rangeBand)))
                .filter(die -> die.count() > 0)
                .toList();
        if (dice.isEmpty()) return null;
        boolean generic = dice.stream().anyMatch(die -> "generic".equalsIgnoreCase(die.color()));
        return new AttackPlan(generic ? "front" : arc, rangeBand, dice, generic);
    }

    private static int eligibleDice(String color, int count, String rangeBand) {
        if ("generic".equalsIgnoreCase(color)) return count;
        if ("short".equals(rangeBand)) return count;
        if ("medium".equals(rangeBand)) return "red".equalsIgnoreCase(color) || "blue".equalsIgnoreCase(color)
                ? count : 0;
        return "red".equalsIgnoreCase(color) ? count : 0;
    }

    private static String rangeBand(double distance, GameState state) {
        double shortRange = numericOption(state.values(), "shortRange", 100);
        double mediumRange = numericOption(state.values(), "mediumRange", 200);
        double longRange = numericOption(state.values(), "longRange", 300);
        if (distance <= shortRange) return "short";
        if (distance <= mediumRange) return "medium";
        if (distance <= longRange) return "long";
        return null;
    }

    private static String firingArc(UnitState source, UnitState target) {
        double angle = Math.toDegrees(Math.atan2(target.positionY() - source.positionY(),
                target.positionX() - source.positionX()));
        return zoneForBearing(normalizeBearing(angle - source.facing()));
    }

    private static String targetZone(UnitState target, UnitState attacker) {
        double angle = Math.toDegrees(Math.atan2(attacker.positionY() - target.positionY(),
                attacker.positionX() - target.positionX()));
        return zoneForBearing(normalizeBearing(angle - target.facing()));
    }

    private static String zoneForBearing(double bearing) {
        if (bearing >= -45 && bearing <= 45) return "front";
        if (bearing > 45 && bearing < 135) return "left";
        if (bearing < -45 && bearing > -135) return "right";
        return "rear";
    }

    private static double normalizeBearing(double bearing) {
        double normalized = bearing % 360;
        if (normalized > 180) normalized -= 360;
        if (normalized < -180) normalized += 360;
        return normalized;
    }

    private record AttackPlan(String arc, String rangeBand, List<AttackDie> dice, boolean generic) {}

    public static final class InvalidSetupException extends RuntimeException {
        private final SetupValidation validation;

        public InvalidSetupException(SetupValidation validation) {
            super("Game setup failed validation");
            this.validation = validation;
        }

        public SetupValidation validation() {
            return validation;
        }
    }
}
