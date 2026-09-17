package com.metis.engine.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfPlaySimulatorTest {
    private final SelfPlaySimulator simulator = new SelfPlaySimulator();

    @ParameterizedTest
    @ValueSource(longs = {1, 42, 2025})
    void cpuPlayersCompleteSeveralRounds(long seed) {
        SelfPlaySimulator.SimulationResult result = simulator.play(seed, 3);

        assertTrue(result.acceptedActions() > 0);
        assertEquals(4, result.finalState().round());
        assertEquals("command", result.finalState().phaseCode());
        assertEquals(2, result.finalState().units().size());
        assertEquals(result.acceptedActions(), result.finalState().stateVersion());
        assertEquals(result.acceptedActions(), result.finalState().events().size());
        assertTrue(result.finalState().events().stream()
                .anyMatch(event -> "unit_attacked".equals(event.type())));
    }

    @Test
    void identicalSeedsProduceIdenticalSelfPlay() {
        SelfPlaySimulator.SimulationResult first = simulator.play(42, 3);
        SelfPlaySimulator.SimulationResult second = simulator.play(42, 3);
        SelfPlaySimulator.SimulationResult different = simulator.play(43, 3);

        assertEquals(first.finalState(), second.finalState());
        assertNotEquals(first.finalState(), different.finalState());
    }
}
