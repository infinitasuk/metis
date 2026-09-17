package com.metis.engine.core;

import java.util.Map;
import java.util.List;

/** Minimal runtime state contract independent of persistence or transport. */
public record GameState(long sessionId, long definitionVersionId, String status,
                        int round, String phaseCode, Long activePlayerId, long stateVersion,
                        List<Player> players, List<UnitState> units, BoardState board,
                        List<GameEvent> events, Map<String, Object> values) {
    public GameState {
        if (sessionId < 1 || definitionVersionId < 1) {
            throw new IllegalArgumentException("session and definition identifiers must be positive");
        }
        if (status == null || status.isBlank() || phaseCode == null || phaseCode.isBlank()) {
            throw new IllegalArgumentException("status and phaseCode are required");
        }
        if (round < 0 || stateVersion < 0) {
            throw new IllegalArgumentException("round and stateVersion must not be negative");
        }
        players = players == null ? List.of() : List.copyOf(players);
        units = units == null ? List.of() : List.copyOf(units);
        events = events == null ? List.of() : List.copyOf(events);
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
