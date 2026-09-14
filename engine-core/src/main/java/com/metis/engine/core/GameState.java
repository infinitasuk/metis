package com.metis.engine.core;

import java.util.Map;

/** Minimal runtime state contract independent of persistence or transport. */
public record GameState(long sessionId, long definitionVersionId, String status,
                        int round, String phaseCode, long stateVersion,
                        Map<String, Object> values) {
    public GameState {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
