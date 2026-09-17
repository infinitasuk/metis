package com.metis.engine.core;

import java.util.List;
import java.util.Map;

/** Small deterministic CPU-vs-CPU driver used by engine tests. */
final class SelfPlaySimulator {
    SimulationResult play(long seed, int rounds) {
        if (rounds < 1) {
            throw new IllegalArgumentException("rounds must be positive");
        }
        GameDefinition definition = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("cpu-demo-unit", "rebel", 50, 120, 1, 100, 1, 0, 0),
                        new UnitDefinition("cpu-demo-unit-empire", "empire", 50, 120, 1, 100, 1, 0, 0)));
        GameSetup setup = new GameSetup(2, 2, List.of(
                new Player(101, 1, "cpu", "rebel"),
                new Player(102, 2, "cpu", "empire")),
                new BoardState("standard", 360, 180),
                Map.of("maximumMovementDistance", 50, "maximumAttackRange", 120));
        RandomSource random = new SeededRandomSource(seed);
        GameEngine engine = new AuthoritativeGameEngine(new SeededRandomSource(seed));
        GameState state = engine.createInitialState(1, definition, setup);
        int acceptedActions = 0;
        int decisionLimit = 1 + 2 + 1 + rounds * 8;

        while (state.round() <= rounds && acceptedActions < decisionLimit) {
            Action action = choose(state, engine.legalActions(definition, state), random);
            if (action == null) {
                throw new IllegalStateException("CPU could not choose an action in " + state.phaseCode());
            }
            ActionResult result = engine.applyAction(definition, state, action);
            if (!result.accepted()) {
                throw new IllegalStateException("CPU selected rejected action: " + result.code());
            }
            state = result.resultingState();
            acceptedActions++;
        }
        if (state.round() <= rounds) {
            throw new IllegalStateException("CPU simulation did not complete");
        }
        return new SimulationResult(state, acceptedActions);
    }

    private Action choose(GameState state, List<LegalAction> legalActions, RandomSource random) {
        if ("setup".equals(state.status())) {
            return new Action("start_game", 0, Map.of());
        }
        if ("deployment".equals(state.phaseCode())) {
            for (LegalAction legal : legalActions) {
                if (!"deploy_unit".equals(legal.type())) {
                    continue;
                }
                boolean alreadyDeployed = state.units().stream()
                        .anyMatch(unit -> unit.playerId() == legal.actorPlayerId());
                if (!alreadyDeployed) {
                    Player player = state.players().stream()
                            .filter(item -> item.playerId() == legal.actorPlayerId())
                            .findFirst().orElseThrow();
                    double x = player.seatNumber() == 1 ? state.board().width() * .1 : state.board().width() * .9;
                    double y = 40 + random.nextInt(100);
                    return new Action("deploy_unit", player.playerId(), Map.of(
                            "unitId", player.playerId() * 10,
                            "unitCode", player.seatNumber() == 1 ? "cpu-demo-unit" : "cpu-demo-unit-empire",
                            "positionX", x,
                            "positionY", y,
                            "facing", (double) random.nextInt(360)));
                }
            }
            return new Action("advance_phase", 0, Map.of());
        }
        if ("command".equals(state.phaseCode()) || "status".equals(state.phaseCode())) {
            return new Action("advance_phase", 0, Map.of());
        }
        for (LegalAction legal : legalActions) {
            if ("attack_unit".equals(legal.type())) {
                UnitState attacker = state.units().stream()
                        .filter(unit -> unit.playerId() == legal.actorPlayerId() && "ready".equals(unit.status()))
                        .findFirst().orElseThrow();
                UnitState target = state.units().stream()
                        .filter(unit -> unit.playerId() != attacker.playerId() && unit.hullPoints() > 0)
                        .filter(unit -> Math.hypot(unit.positionX() - attacker.positionX(),
                                unit.positionY() - attacker.positionY()) <= 120)
                        .findFirst().orElse(null);
                if (target != null) {
                    return new Action("attack_unit", attacker.playerId(), Map.of(
                            "unitId", attacker.unitId(), "targetUnitId", target.unitId()));
                }
            }
        }
        for (LegalAction legal : legalActions) {
            if ("move_unit".equals(legal.type())) {
                UnitState unit = state.units().stream()
                        .filter(item -> item.playerId() == legal.actorPlayerId() && "ready".equals(item.status()))
                        .findFirst().orElseThrow();
                Player player = state.players().stream()
                        .filter(item -> item.playerId() == legal.actorPlayerId())
                        .findFirst().orElseThrow();
                 double distance = 40 + random.nextInt(11);
                double direction = player.seatNumber() == 1 ? 1 : -1;
                double x = unit.positionX() + direction * distance;
                if (x < 0 || x > state.board().width()) {
                    x = unit.positionX() - direction * distance;
                }
                return new Action("move_unit", player.playerId(), Map.of(
                        "unitId", unit.unitId(),
                        "positionX", x,
                        "positionY", unit.positionY(),
                        "facing", (double) random.nextInt(360)));
            }
        }
        return legalActions.stream()
                .filter(action -> "end_turn".equals(action.type()))
                .findFirst()
                .map(action -> new Action(action.type(), action.actorPlayerId(), Map.of()))
                .orElse(null);
    }

    record SimulationResult(GameState finalState, int acceptedActions) {}
}
