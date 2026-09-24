package com.metis.engine.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionAuthorizationTest {
    private GameState state(String phase, Long activePlayer) {
        return new GameState(1, 1, "running", 1, phase, activePlayer, 5,
                List.of(new Player(10, 1, "human", "a"), new Player(20, 2, "human", "b")),
                List.of(new UnitState(1, 10, "a", 100, 100, 0, "ready", 10),
                        new UnitState(2, 20, "b", 150, 100, 180, "ready", 10)),
                new BoardState("test", 1000, 1000), List.of(), Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"entity_activation", "shooting", "grid_attack"})
    void turnOwnershipDoesNotDependOnGameOrPhaseNames(String phase) {
        assertTrue(ActionAuthorization.isActivePlayer(state(phase, 10L), 10));
        assertFalse(ActionAuthorization.isActivePlayer(state(phase, 10L), 20));
        assertFalse(ActionAuthorization.isActivePlayer(state(phase, null), 10));
        assertFalse(ActionAuthorization.isActivePlayer(state(phase, 99L), 99));
    }

    @Test
    void rejectsOutOfTurnAttacksBeforeRandomnessOrStateChanges() {
        AtomicInteger rolls = new AtomicInteger();
        AuthoritativeGameEngine engine = new AuthoritativeGameEngine(bound -> { rolls.incrementAndGet(); return 5; });
        GameState state = state("ship", 10L);
        ActionResult result = engine.applyAction(state,
                new Action("attack_unit", 20, Map.of("unitId", 2L, "targetUnitId", 1L)));
        assertFalse(result.accepted());
        assertEquals("NOT_ACTIVE_PLAYER", result.code());
        assertEquals(0, rolls.get());
        assertEquals(5, state.stateVersion());
        assertEquals(10, state.units().getFirst().hullPoints());
        assertTrue(state.events().isEmpty());

        ActionResult permitted = engine.applyAction(state,
                new Action("attack_unit", 10, Map.of("unitId", 1L, "targetUnitId", 2L)));
        assertTrue(permitted.accepted());
        assertTrue(rolls.get() > 0);
    }
}
