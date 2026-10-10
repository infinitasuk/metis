package com.metis.engine.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthoritativeGameEngineTest {

    private final GameDefinition definition = new GameDefinition(
            1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2);
    private final AuthoritativeGameEngine engine = new AuthoritativeGameEngine(bound -> 5);

    @Test
    void createsDeterministicInitialStateForValidSetup() {
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), null);

        GameState state = engine.createInitialState(99, definition, setup);

        assertEquals(99, state.sessionId());
        assertEquals(4, state.definitionVersionId());
        assertEquals("setup", state.status());
        assertEquals(2, state.players().size());
        assertTrue(state.units().isEmpty());
        assertEquals(0, state.stateVersion());
    }

    @Test
    void rejectsInvalidSetupBeforeStateCreation() {
        GameSetup setup = new GameSetup(1, 1, List.of(
                new Player(10, 1, "cpu", "rebel")),
                new BoardState("standard", 360, 180), null);

        SetupValidation validation = engine.validateSetup(definition, setup);

        assertFalse(validation.valid());
        assertTrue(validation.violations().stream().anyMatch(item -> "PLAYER_COUNT_INVALID".equals(item.code())));
        assertThrows(AuthoritativeGameEngine.InvalidSetupException.class,
                () -> engine.createInitialState(99, definition, setup));
    }

    @Test
    void appliesDeploymentAndBoundedMovementAsImmutableTransitions() {
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), Map.of("maximumMovementDistance", 50));
        GameState initial = engine.createInitialState(99, definition, setup);

        GameState running = engine.applyAction(initial, new Action("start_game", 0, null)).resultingState();
        GameState deployed = engine.applyAction(running, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "cr90", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        GameState command = engine.applyAction(deployed, new Action("advance_phase", 0, null)).resultingState();
        GameState movement = engine.applyAction(command, new Action("advance_phase", 0, null)).resultingState();
        ActionResult moved = engine.applyAction(movement, new Action("move_unit", 10, Map.of(
                "unitId", 100L, "positionX", 50, "positionY", 20, "facing", 15)));

        assertTrue(moved.accepted());
        assertEquals(5, moved.resultingState().stateVersion());
        assertEquals(5, moved.resultingState().events().size());
        assertEquals(50, moved.resultingState().units().getFirst().positionX());
        assertEquals(15.0, ((Number) moved.resultingState().events().getLast().payload().get("facing")).doubleValue(), .001);
        assertEquals("activated", moved.resultingState().units().getFirst().status());
        assertEquals(0, initial.stateVersion());

        ActionResult repeated = engine.applyAction(moved.resultingState(), new Action("move_unit", 10, Map.of(
                "unitId", 100L, "positionX", 55, "positionY", 20, "facing", 15)));

        assertTrue(repeated.accepted());
        assertEquals(55, repeated.resultingState().units().getFirst().positionX());
    }

    @Test
    void configuredMovementLimitOverridesRawProfileSpeed() {
        GameDefinition profileDefinition = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("cr90", "rebel", 4, 100, 1, 5),
                        new UnitDefinition("empire-unit", "empire", 4, 100, 1, 5)));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), Map.of("maximumMovementDistance", 50));
        GameState state = engine.createInitialState(99, profileDefinition, setup);
        state = engine.applyAction(profileDefinition, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(profileDefinition, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "cr90", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(profileDefinition, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(profileDefinition, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult moved = engine.applyAction(profileDefinition, state, new Action("move_unit", 10, Map.of(
                "unitId", 100L, "positionX", 60, "positionY", 20, "facing", 0)));

        assertTrue(moved.accepted());
        assertEquals(60, moved.resultingState().units().getFirst().positionX());
    }

    @Test
    void rejectsMovementByAnotherPlayerOrBeyondTheBoard() {
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), Map.of("maximumMovementDistance", 50));
        GameState state = engine.createInitialState(99, definition, setup);
        state = engine.applyAction(state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "cr90", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult notOwned = engine.applyAction(state, new Action("move_unit", 11, Map.of(
                "unitId", 100L, "positionX", 25, "positionY", 20, "facing", 0)));
        ActionResult offBoard = engine.applyAction(state, new Action("move_unit", 10, Map.of(
                "unitId", 100L, "positionX", 20, "positionY", -1, "facing", 0)));

        assertFalse(notOwned.accepted());
        assertEquals("UNIT_NOT_OWNED", notOwned.code());
        assertFalse(offBoard.accepted());
        assertEquals("POSITION_OUT_OF_BOUNDS", offBoard.code());
    }

    @Test
    void seededRandomnessIsRepeatable() {
        RandomSource first = new SeededRandomSource(42);
        RandomSource second = new SeededRandomSource(42);

        assertEquals(first.nextInt(1000), second.nextInt(1000));
        assertEquals(first.nextInt(1000), second.nextInt(1000));
    }

    @Test
    void exposesLegalActionsAndAdvancesTheActivePlayer() {
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), Map.of("maximumMovementDistance", 50));
        GameState state = engine.createInitialState(99, definition, setup);

        assertEquals("start_game", engine.legalActions(state).getFirst().type());
        state = engine.applyAction(state, new Action("start_game", 0, null)).resultingState();
        assertTrue(engine.legalActions(state).stream().anyMatch(action -> "deploy_unit".equals(action.type())));
        state = engine.applyAction(state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "cr90", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        assertTrue(engine.legalActions(state).stream().anyMatch(action -> "advance_phase".equals(action.type())));
        state = engine.applyAction(state, new Action("advance_phase", 0, null)).resultingState();
        assertEquals("command", state.phaseCode());
        state = engine.applyAction(state, new Action("advance_phase", 0, null)).resultingState();
        assertEquals("ship", state.phaseCode());
        assertEquals(10, state.activePlayerId());
        assertTrue(engine.legalActions(state).stream().anyMatch(action -> "end_turn".equals(action.type())));

        ActionResult ended = engine.applyAction(state, new Action("end_turn", 10, null));

        assertTrue(ended.accepted());
        assertEquals(11, ended.resultingState().activePlayerId());
        assertEquals(1, ended.resultingState().round());
    }

    @Test
    void advancesThroughTheOfficialRoundPhases() {
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), Map.of("maximumMovementDistance", 50));
        GameState state = engine.createInitialState(99, definition, setup);
        state = engine.applyAction(state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "cr90", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "cr90", "positionX", 40, "positionY", 20, "facing", 0))).resultingState();

        state = engine.applyAction(state, new Action("advance_phase", 0, null)).resultingState();
        assertEquals("command", state.phaseCode());
        state = engine.applyAction(state, new Action("advance_phase", 0, null)).resultingState();
        assertEquals("ship", state.phaseCode());
        state = engine.applyAction(state, new Action("end_turn", 10, null)).resultingState();
        state = engine.applyAction(state, new Action("end_turn", 11, null)).resultingState();
        assertEquals("squadron", state.phaseCode());
        state = engine.applyAction(state, new Action("end_turn", 10, null)).resultingState();
        state = engine.applyAction(state, new Action("end_turn", 11, null)).resultingState();
        assertEquals("status", state.phaseCode());
        state = engine.applyAction(state, new Action("advance_phase", 0, null)).resultingState();
        assertEquals("command", state.phaseCode());
        assertEquals(2, state.round());
    }

    @Test
    void finishesAtTheConfiguredRoundLimit() {
        GameState state = new GameState(99, 4, "running", 6, "status", null, 0,
                List.of(new Player(10, 1, "cpu", "rebel"), new Player(11, 2, "cpu", "empire")),
                List.of(), new BoardState("standard", 360, 180), List.of(), Map.of("maximumRounds", 6));

        ActionResult ended = engine.applyAction(state, new Action("advance_phase", 0, Map.of()));

        assertTrue(ended.accepted());
        assertEquals("finished", ended.resultingState().status());
        assertEquals("victory", ended.resultingState().phaseCode());
        assertEquals("round_limit_draw", ended.resultingState().values().get("outcome"));
        assertEquals("round_limit_reached", ended.resultingState().events().getLast().type());
    }

    @Test
    void unlimitedModeContinuesPastTheStandardRoundLimit() {
        GameState state = new GameState(99, 4, "running", 6, "status", null, 0,
                List.of(new Player(10, 1, "cpu", "rebel"), new Player(11, 2, "cpu", "empire")),
                List.of(), new BoardState("standard", 360, 180), List.of(),
                Map.of("maximumRounds", 6, "unlimitedRounds", true));

        ActionResult nextRound = engine.applyAction(state, new Action("advance_phase", 0, Map.of()));

        assertTrue(nextRound.accepted());
        assertEquals("running", nextRound.resultingState().status());
        assertEquals("command", nextRound.resultingState().phaseCode());
        assertEquals(7, nextRound.resultingState().round());
    }

    @Test
    void validatesFactionsAndUnitsAgainstTheDefinitionRevision() {
        GameDefinition catalogue = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("rebel-unit", "rebel", 20),
                        new UnitDefinition("empire-unit", "empire", 20)));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "unknown"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), null);

        SetupValidation validation = engine.validateSetup(catalogue, setup);

        assertFalse(validation.valid());
        assertTrue(validation.violations().stream().anyMatch(item -> "FACTION_INVALID".equals(item.code())));
    }

    @Test
    void rejectsUnknownAndOpposingFactionUnits() {
        GameDefinition catalogue = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("rebel-unit", "rebel", 20),
                        new UnitDefinition("empire-unit", "empire", 20)));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();

        ActionResult unknown = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "missing-unit", "positionX", 20, "positionY", 20, "facing", 0)));
        ActionResult opposing = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 101L, "unitCode", "empire-unit", "positionX", 20, "positionY", 20, "facing", 0)));

        assertEquals("UNIT_DEFINITION_NOT_FOUND", unknown.code());
        assertEquals("UNIT_FACTION_MISMATCH", opposing.code());
    }

    @Test
    void attacksHostileUnitInRangeAndAppliesProfileDamage() {
        GameDefinition catalogue = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("rebel-unit", "rebel", 20, 30, 2, 3),
                        new UnitDefinition("empire-unit", "empire", 20, 30, 1, 3)));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "rebel-unit", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "empire-unit", "positionX", 40, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult attack = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L)));

        assertTrue(attack.accepted());
        assertEquals(1, attack.resultingState().units().get(1).hullPoints());
        assertEquals("activated", attack.resultingState().units().get(0).status());
        Map<String, Object> roll = ResponseWindows.object(attack.resultingState().values().get("attackRoll"));
        assertEquals("resolved", roll.get("status"));
        assertEquals(1, roll.get("attackDice"));
        assertEquals(1, roll.get("rawHits"));
        assertEquals(1, ((List<?>) roll.get("dice")).size());
        Map<?, ?> die = (Map<?, ?>) ((List<?>) roll.get("dice")).getFirst();
        assertEquals(6, die.get("face"));
        assertEquals(true, die.get("hit"));
        assertEquals(state.events().size() + 1, attack.resultingState().events().size());
        assertEquals("unit_attacked", attack.resultingState().events().getLast().type());
        assertEquals(roll, attack.resultingState().events().getLast().payload().get("roll"));
        assertTrue(engine.legalActions(catalogue, state).stream()
                .anyMatch(action -> "attack_unit".equals(action.type())));

        ActionResult repeated = engine.applyAction(catalogue, attack.resultingState(), new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L)));
        assertEquals("UNIT_ALREADY_ACTIVATED", repeated.code());
    }

    @Test
    void rejectsFriendlyAndOutOfRangeAttacks() {
        GameDefinition catalogue = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("rebel-unit", "rebel", 20, 10, 1, 3),
                        new UnitDefinition("empire-unit", "empire", 20, 10, 1, 3)));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "rebel-unit", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "empire-unit", "positionX", 40, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult friendly = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 100L)));
        ActionResult distant = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L)));

        assertEquals("TARGET_NOT_HOSTILE", friendly.code());
        assertEquals("ATTACK_OUT_OF_RANGE", distant.code());
    }

    @Test
    void usesColoredArcDiceRangeBandsAndTargetShieldZones() {
        UnitDefinition attacker = new UnitDefinition("attacker", "rebel", 3, 300, 1, 4,
                2, 0, 0, "Attacker", "Ship", Map.of(), 50,
                Map.of("front", List.of(new AttackDie("red", 2))), List.of(), Map.of(), List.of());
        UnitDefinition target = new UnitDefinition("target", "empire", 3, 300, 1, 4,
                1, 10, 0, "Target", "Ship", Map.of(), 50,
                Map.of("front", List.of(new AttackDie("red", 1))), List.of(),
                Map.of("front", 2, "left", 3, "right", 3, "rear", 2), List.of());
        GameDefinition catalogue = new GameDefinition(1, "armada", "Armada", 4, "armada_1_5",
                2, 2, 0, 2, List.of("rebel", "empire"), List.of(attacker, target));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"), new Player(11, 2, "human", "empire")),
                new BoardState("standard", 500, 500), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "attacker", "positionX", 100, "positionY", 100, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "target", "positionX", 250, "positionY", 100, "facing", 180))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult attack = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L)));

        assertTrue(attack.accepted());
        assertEquals("front", attack.resultingState().events().getLast().payload().get("arc"));
        assertEquals("medium", attack.resultingState().events().getLast().payload().get("rangeBand"));
        assertEquals("front", attack.resultingState().events().getLast().payload().get("targetZone"));
        assertEquals(0, attack.resultingState().units().get(1).shieldZones().get("front"));
        assertEquals(8, attack.resultingState().units().get(1).shieldPoints());
    }

    @Test
    void rejectsAnAttackOutsideTheWeaponArc() {
        UnitDefinition attacker = new UnitDefinition("attacker", "rebel", 3, 300, 1, 4,
                1, 0, 0, "Attacker", "Ship", Map.of(), 50,
                Map.of("front", List.of(new AttackDie("red", 1))), List.of(), Map.of(), List.of());
        UnitDefinition target = new UnitDefinition("target", "empire", 3, 300, 1, 4);
        GameDefinition catalogue = new GameDefinition(1, "armada", "Armada", 4, "armada_1_5",
                2, 2, 0, 2, List.of("rebel", "empire"), List.of(attacker, target));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"), new Player(11, 2, "human", "empire")),
                new BoardState("standard", 500, 500), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "attacker", "positionX", 100, "positionY", 100, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "target", "positionX", 100, "positionY", 250, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();

        assertFalse(engine.canAttack(catalogue, state, state.units().get(0), state.units().get(1)));
        assertTrue(engine.legalActions(catalogue, state).stream()
                .noneMatch(action -> "attack_unit".equals(action.type())));
    }

    @Test
    void resolvesAttackDiceAgainstDefenseTokensAndShields() {
        GameDefinition catalogue = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("rebel-unit", "rebel", 20, 30, 1, 3, 2, 0, 0),
                        new UnitDefinition("empire-unit", "empire", 20, 30, 1, 3, 1, 2, 1)));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "rebel-unit", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "empire-unit", "positionX", 40, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult attack = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L)));
        UnitState target = attack.resultingState().units().get(1);

        assertTrue(attack.accepted());
        assertEquals(2, attack.resultingState().events().getLast().payload().get("hits"));
        assertEquals(3, target.hullPoints());
        assertEquals(1, target.shieldPoints());
        assertEquals(0, target.defenseTokens());
        assertEquals(true, attack.resultingState().events().getLast().payload().get("defenseTokenSpent"));
    }

    @Test
    void spendsTheSelectedDefenseTokenAndReadiesItAtStatus() {
        UnitDefinition attacker = new UnitDefinition("attacker", "rebel", 3, 300, 1, 4,
                2, 0, 0, "Attacker", "Ship", Map.of(), 50,
                Map.of("front", List.of(new AttackDie("red", 2))), List.of(), Map.of(), List.of(),
                Map.of(), 3, 4, 2);
        UnitDefinition target = new UnitDefinition("target", "empire", 3, 300, 1, 4,
                1, 0, 0, "Target", "Ship", Map.of(), 50,
                Map.of("front", List.of(new AttackDie("red", 1))), List.of(), Map.of(), List.of(),
                Map.of("brace", 1, "evade", 1), 2, 3, 1);
        assertEquals(2, target.commandValue());
        assertEquals(3, target.engineeringValue());
        assertEquals(1, target.squadronValue());
        GameDefinition catalogue = new GameDefinition(1, "armada", "Armada", 4, "armada_1_5",
                2, 2, 0, 2, List.of("rebel", "empire"), List.of(attacker, target));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"), new Player(11, 2, "human", "empire")),
                new BoardState("standard", 500, 500), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "attacker", "positionX", 100, "positionY", 100, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "target", "positionX", 150, "positionY", 100, "facing", 180))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult attack = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L, "defenseTokenType", "brace")));
        UnitState damaged = attack.resultingState().units().get(1);

        assertTrue(attack.accepted());
        assertEquals("brace", attack.resultingState().events().getLast().payload().get("defenseTokenType"));
        assertEquals(0, damaged.readyDefenseTokens().getOrDefault("brace", 0));
        assertEquals(1, damaged.readyDefenseTokens().get("evade"));
        assertEquals(1, damaged.exhaustedDefenseTokens().get("brace"));
        assertEquals(1, damaged.defenseTokens());

        GameState status = new GameState(99, 4, "running", 1, "status", null,
                attack.resultingState().stateVersion(), attack.resultingState().players(),
                attack.resultingState().units(), attack.resultingState().board(),
                attack.resultingState().events(), Map.of("maximumRounds", 6));
        ActionResult refreshed = engine.applyAction(catalogue, status, new Action("advance_phase", 0, Map.of()));
        UnitState readied = refreshed.resultingState().units().get(1);

        assertTrue(refreshed.accepted());
        assertEquals(1, readied.readyDefenseTokens().get("brace"));
        assertEquals(1, readied.readyDefenseTokens().get("evade"));
        assertTrue(readied.exhaustedDefenseTokens().isEmpty());
        assertEquals(2, readied.defenseTokens());
    }

    @Test
    void rejectsADefenseTokenThatIsNotReady() {
        UnitDefinition attacker = new UnitDefinition("attacker", "rebel", 3, 300, 1, 4,
                1, 0, 0, "Attacker", "Ship", Map.of(), 50,
                Map.of("front", List.of(new AttackDie("red", 1))), List.of(), Map.of(), List.of(),
                Map.of(), 2, 3, 1);
        UnitDefinition target = new UnitDefinition("target", "empire", 3, 300, 1, 4,
                1, 0, 0, "Target", "Ship", Map.of(), 50,
                Map.of("front", List.of(new AttackDie("red", 1))), List.of(), Map.of(), List.of(),
                Map.of("brace", 1), 2, 3, 1);
        GameState state = new GameState(99, 4, "running", 1, "ship", 10L, 0,
                List.of(new Player(10, 1, "human", "rebel"), new Player(11, 2, "human", "empire")),
                List.of(new UnitState(100, 10, "attacker", 100, 100, 0, "ready", 4, 0, 0, 1,
                                Map.of(), Map.of(), Map.of()),
                        new UnitState(101, 11, "target", 150, 100, 180, "ready", 4, 0, 0, 1,
                                Map.of(), Map.of(), Map.of("brace", 1))),
                new BoardState("standard", 500, 500), List.of(), Map.of());
        GameDefinition catalogue = new GameDefinition(1, "armada", "Armada", 4, "armada_1_5",
                2, 2, 0, 2, List.of("rebel", "empire"), List.of(attacker, target));

        ActionResult result = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L, "defenseTokenType", "evade")));

        assertFalse(result.accepted());
        assertEquals("DEFENSE_TOKEN_NOT_READY", result.code());
    }

    @Test
    void endsTheMatchWhenTheLastOpposingUnitIsDestroyed() {
        GameDefinition catalogue = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                        new UnitDefinition("rebel-unit", "rebel", 20, 30, 1, 3, 1, 0, 0),
                        new UnitDefinition("empire-unit", "empire", 20, 30, 1, 1, 1, 0, 0)));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"),
                new Player(11, 2, "human", "empire")),
                new BoardState("standard", 360, 180), null);
        GameState state = engine.createInitialState(99, catalogue, setup);
        state = engine.applyAction(catalogue, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 100L, "unitCode", "rebel-unit", "positionX", 20, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 101L, "unitCode", "empire-unit", "positionX", 40, "positionY", 20, "facing", 0))).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(catalogue, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult result = engine.applyAction(catalogue, state, new Action("attack_unit", 10, Map.of(
                "unitId", 100L, "targetUnitId", 101L)));

        assertTrue(result.accepted());
        assertEquals("finished", result.resultingState().status());
        assertEquals("victory", result.resultingState().phaseCode());
        assertEquals(10L, result.resultingState().values().get("winnerPlayerId"));
        assertEquals("game_won", result.resultingState().events().getLast().type());
        assertTrue(engine.legalActions(catalogue, result.resultingState()).isEmpty());
    }

    @Test
    void appliesAProfileBackedManeuverAndPersistsTheNewSpeed() {
        GameDefinition profileDefinition = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
                List.of("rebel", "empire"), List.of(
                new UnitDefinition("ship", "rebel", 3, 100, 1, 5, 1, 0, 0, "Ship", "Ship"),
                new UnitDefinition("enemy", "empire", 3, 100, 1, 5, 1, 0, 0, "Enemy", "Ship")));
        GameSetup setup = new GameSetup(2, 0, List.of(
                new Player(10, 1, "human", "rebel"), new Player(11, 2, "human", "empire")),
                new BoardState("standard", 500, 500), Map.of("distancePerSpeed", 100));
        GameState state = engine.createInitialState(99, profileDefinition, setup);
        state = engine.applyAction(profileDefinition, state, new Action("start_game", 0, null)).resultingState();
        state = engine.applyAction(profileDefinition, state, new Action("deploy_unit", 10, Map.of(
                "unitId", 1L, "unitCode", "ship", "positionX", 100, "positionY", 100, "facing", 0))).resultingState();
        state = engine.applyAction(profileDefinition, state, new Action("deploy_unit", 11, Map.of(
                "unitId", 2L, "unitCode", "enemy", "positionX", 450, "positionY", 450, "facing", 180))).resultingState();
        state = engine.applyAction(profileDefinition, state, new Action("advance_phase", 0, null)).resultingState();
        state = engine.applyAction(profileDefinition, state, new Action("advance_phase", 0, null)).resultingState();

        ActionResult moved = engine.applyAction(profileDefinition, state, new Action("move_unit", 10, Map.of(
                "unitId", 1L, "speed", 2, "yaw", List.of(1, 0))));

        assertTrue(moved.accepted());
        assertEquals(2, moved.resultingState().units().getFirst().currentSpeed());
        assertEquals(22.5, moved.resultingState().units().getFirst().facing(), .001);
        assertEquals(292.39, moved.resultingState().units().getFirst().positionX(), .01);
        assertEquals(138.27, moved.resultingState().units().getFirst().positionY(), .01);
    }

    @Test
    void rejectsYawOutsideTheManeuverChart() {
        GameDefinition profileDefinition = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 1, 1, 0, 0,
                List.of("rebel"), List.of(new UnitDefinition("ship", "rebel", 3)));
        GameState state = new GameState(99, 4, "running", 1, "ship", 10L, 0,
                List.of(new Player(10, 1, "human", "rebel")),
                List.of(new UnitState(1, 10, "ship", 100, 100, 0, "ready", 1, 0, 0, 1)),
                new BoardState("standard", 500, 500), List.of(), Map.of("distancePerSpeed", 100));

        ActionResult result = engine.applyAction(profileDefinition, state, new Action("move_unit", 10, Map.of(
                "unitId", 1L, "speed", 1, "yaw", List.of(2, 0))));

        assertFalse(result.accepted());
        assertEquals("MANEUVER_INVALID", result.code());
    }

    @Test
    void rejectsAManeuverWhenTheBaseLeavesTheBoard() {
        GameDefinition profileDefinition = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 1, 1, 0, 0,
                List.of("rebel"), List.of(new UnitDefinition("ship", "rebel", 3)));
        GameState state = new GameState(99, 4, "running", 1, "ship", 10L, 0,
                List.of(new Player(10, 1, "human", "rebel")),
                List.of(new UnitState(1, 10, "ship", 450, 250, 0, "ready", 1, 0, 0, 1)),
                new BoardState("standard", 500, 500), List.of(), Map.of("distancePerSpeed", 100));

        ActionResult result = engine.applyAction(profileDefinition, state, new Action("move_unit", 10, Map.of(
                "unitId", 1L, "speed", 1, "yaw", List.of(0))));

        assertFalse(result.accepted());
        assertEquals("POSITION_OUT_OF_BOUNDS", result.code());
    }

    @Test
    void rejectsAManeuverThatCrossesAnotherBase() {
        GameDefinition profileDefinition = new GameDefinition(
                1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 0,
                List.of("rebel", "empire"), List.of(
                new UnitDefinition("ship", "rebel", 3), new UnitDefinition("enemy", "empire", 3)));
        GameState state = new GameState(99, 4, "running", 1, "ship", 10L, 0,
                List.of(new Player(10, 1, "human", "rebel"), new Player(11, 2, "human", "empire")),
                List.of(new UnitState(1, 10, "ship", 100, 250, 0, "ready", 1, 0, 0, 1),
                        new UnitState(2, 11, "enemy", 220, 250, 0, "ready", 1, 0, 0, 1)),
                new BoardState("standard", 500, 500), List.of(), Map.of("distancePerSpeed", 100));

        ActionResult result = engine.applyAction(profileDefinition, state, new Action("move_unit", 10, Map.of(
                "unitId", 1L, "speed", 1, "yaw", List.of(0))));

        assertFalse(result.accepted());
        assertEquals("MOVEMENT_COLLISION", result.code());
    }
}
