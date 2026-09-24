package com.metis.engine.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementResetTest {
    private final AuthoritativeGameEngine engine = new AuthoritativeGameEngine(bound -> 5);
    private final GameDefinition definition = new GameDefinition(
            1, "armada", "Armada", 4, "armada_1_5", 2, 2, 0, 2,
            List.of("rebel", "empire"), List.of(
            new UnitDefinition("ship", "rebel", 3, 100, 1, 5, 1, 0, 0, "Ship", "Ship"),
            new UnitDefinition("enemy", "empire", 3, 100, 1, 5, 1, 0, 0, "Enemy", "Ship")));

    private GameState initialState() {
        return new GameState(99, 4, "running", 1, "ship", 10L, 0,
                List.of(new Player(10, 1, "human", "rebel"), new Player(11, 2, "human", "empire")),
                List.of(new UnitState(1, 10, "ship", 100, 100, 0, "ready", 5, 1, 0, 1),
                        new UnitState(2, 10, "ship", 100, 500, 90, "ready", 5, 1, 0, 1),
                        new UnitState(3, 11, "enemy", 800, 800, 180, "ready", 5, 1, 0, 1)),
                new BoardState("standard", 1000, 1000), List.of(), Map.of("distancePerSpeed", 100));
    }

    private GameState apply(GameState state, String type, long actor, Map<String, Object> parameters) {
        ActionResult result = engine.applyAction(definition, state, new Action(type, actor, parameters));
        assertTrue(result.accepted(), result.code() + ": " + result.message());
        return result.resultingState();
    }

    private GameState move(GameState state, long id, double x, double y, double facing) {
        return apply(state, "move_unit", 10, Map.of("unitId", id, "positionX", x, "positionY", y, "facing", facing));
    }

    private boolean canReset(GameState state) {
        return engine.legalActions(definition, state).stream().anyMatch(action -> "reset_movement".equals(action.type()));
    }

    @Test
    void resetsDirectMovementAndAllowsAReplacementMove() {
        GameState initial = initialState();
        assertFalse(canReset(initial));
        GameState moved = move(initial, 1, 150, 125, 45);
        assertTrue(canReset(moved));
        // Client-supplied coordinates are ignored: only the stored origin can be restored.
        GameState reset = apply(moved, "reset_movement", 10, Map.of("unitId", 1L, "positionX", 900));
        assertEquals(initial.units(), reset.units());
        assertEquals(moved.stateVersion() + 1, reset.stateVersion());
        assertEquals("activated", moved.units().getFirst().status());
        assertFalse(canReset(reset));
        GameEvent resetEvent = reset.events().getLast();
        assertEquals("unit_movement_reset", resetEvent.type());
        assertEquals(moved.events().getLast().sequence(), resetEvent.payload().get("movementSequence"));
        assertEquals(100.0, resetEvent.payload().get("positionX"));
        assertEquals(0.0, resetEvent.payload().get("facing"));
        assertFalse(engine.applyAction(definition, reset,
                new Action("reset_movement", 10, Map.of("unitId", 1L))).accepted());
        GameState replacement = move(reset, 1, 125, 150, 90);
        assertEquals(initial.units(), apply(replacement, "reset_movement", 10, Map.of("unitId", 1L)).units());
    }

    @Test
    void resetsManeuverSpeedAndHeadingAsWellAsPosition() {
        GameState initial = initialState();
        GameState moved = apply(initial, "move_unit", 10, Map.of("unitId", 1L, "speed", 2, "yaw", List.of(1, 0)));
        assertEquals(2, moved.units().getFirst().currentSpeed());
        assertEquals(22.5, moved.units().getFirst().facing(), .001);
        UnitState position = moved.units().getFirst();
        GameState rotated = move(moved, 1, position.positionX(), position.positionY(), 90);
        assertEquals(2, rotated.units().getFirst().currentSpeed());
        GameState reset = apply(rotated, "reset_movement", 10, Map.of("unitId", 1L));
        assertEquals(initial.units(), reset.units());
        assertEquals(1, reset.events().getLast().payload().get("speed"));
    }

    @Test
    void editsRemainWithinOriginalAllowanceAndResetToOriginalPose() {
        GameState initial = initialState();
        GameState firstMove = move(initial, 1, 175, 100, 0);
        GameState rotated = move(firstMove, 1, 175, 100, 90);
        assertEquals(90, rotated.units().getFirst().facing());
        assertEquals(firstMove.events().getLast().sequence(),
                rotated.events().getLast().payload().get("replacesMovementSequence"));
        ActionResult chained = engine.applyAction(definition, rotated, new Action("move_unit", 10,
                Map.of("unitId", 1L, "positionX", 250, "positionY", 100, "facing", 90)));
        assertFalse(chained.accepted());
        assertEquals("MOVEMENT_DISTANCE_EXCEEDED", chained.code());
        GameState edited = move(rotated, 1, 100, 175, 180);
        assertEquals(initial.units(), apply(edited, "reset_movement", 10, Map.of("unitId", 1L)).units());
        GameState ended = apply(edited, "end_turn", 10, Map.of());
        assertFalse(engine.applyAction(definition, ended, new Action("move_unit", 10,
                Map.of("unitId", 1L, "positionX", 100, "positionY", 150, "facing", 90))).accepted());
    }

    @Test
    void maneuverEditsReplaceThePathInsteadOfAddingMoreTravel() {
        GameState initial = initialState();
        GameState first = apply(initial, "move_unit", 10, Map.of("unitId", 1L, "speed", 2, "yaw", List.of(1, 0)));
        GameState edited = apply(first, "move_unit", 10, Map.of("unitId", 1L, "speed", 2, "yaw", List.of(-1, 0)));
        GameState fromStart = apply(initial, "move_unit", 10, Map.of("unitId", 1L, "speed", 2, "yaw", List.of(-1, 0)));
        assertEquals(fromStart.units(), edited.units());
        assertEquals(initial.units(), apply(edited, "reset_movement", 10, Map.of("unitId", 1L)).units());
    }

    @Test
    void resetsHeadingOnlyAndLeavesOtherUnitsMovementsAlone() {
        GameState initial = initialState();
        GameState rotated = move(initial, 1, 100, 100, 180);
        GameState movedBoth = move(rotated, 2, 100, 550, 90);
        GameState reset = apply(movedBoth, "reset_movement", 10, Map.of("unitId", 1L));
        assertEquals(initial.units().getFirst(), reset.units().getFirst());
        assertEquals(movedBoth.units().get(1), reset.units().get(1));
        assertTrue(canReset(reset));
        assertEquals(initial.units(), apply(reset, "reset_movement", 10, Map.of("unitId", 2L)).units());
    }

    @Test
    void forbidsResetForOtherPlayersAndAfterEndActivation() {
        GameState moved = move(initialState(), 1, 150, 100, 0);
        assertEquals("NOT_ACTIVE_PLAYER", engine.applyAction(definition, moved,
                new Action("reset_movement", 11, Map.of("unitId", 1L))).code());
        assertEquals("UNIT_NOT_OWNED", engine.applyAction(definition, moved,
                new Action("reset_movement", 10, Map.of("unitId", 3L))).code());
        GameState nextPlayer = apply(moved, "end_turn", 10, Map.of());
        assertFalse(canReset(nextPlayer));
        assertFalse(engine.applyAction(definition, nextPlayer,
                new Action("reset_movement", 10, Map.of("unitId", 1L))).accepted());
        GameState nextPhase = apply(nextPlayer, "end_turn", 11, Map.of());
        assertEquals(10L, nextPhase.activePlayerId());
        assertFalse(canReset(nextPhase));
        assertEquals("MOVEMENT_RESET_NOT_ALLOWED", engine.applyAction(definition, nextPhase,
                new Action("reset_movement", 10, Map.of("unitId", 1L))).code());
    }

    @Test
    void doesNotResetAnAttackOrAnUnrecordedLegacyMovement() {
        GameState initial = initialState();
        UnitState attacker = initial.units().getFirst();
        UnitState target = new UnitState(3, 11, "enemy", 150, 100, 180, "ready", 50, 0, 0, 1);
        GameState combat = new GameState(initial.sessionId(), initial.definitionVersionId(), initial.status(),
                initial.round(), initial.phaseCode(), initial.activePlayerId(), initial.stateVersion(),
                initial.players(), List.of(attacker, target), initial.board(), initial.events(), initial.values());
        GameState attacked = apply(combat, "attack_unit", 10, Map.of("unitId", 1L, "targetUnitId", 3L));
        assertFalse(canReset(attacked));
        assertFalse(engine.applyAction(definition, attacked,
                new Action("reset_movement", 10, Map.of("unitId", 1L))).accepted());

        GameState moved = move(initial, 1, 150, 100, 0);
        GameState legacy = new GameState(moved.sessionId(), moved.definitionVersionId(), moved.status(), moved.round(),
                moved.phaseCode(), moved.activePlayerId(), moved.stateVersion(), moved.players(), moved.units(),
                moved.board(), List.of(), moved.values());
        assertFalse(canReset(legacy));
        assertFalse(engine.applyAction(definition, legacy,
                new Action("reset_movement", 10, Map.of("unitId", 1L))).accepted());
    }
}
