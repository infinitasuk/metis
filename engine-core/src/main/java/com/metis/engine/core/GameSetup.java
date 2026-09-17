package com.metis.engine.core;

import java.util.List;
import java.util.Map;

/** Immutable setup submitted to the authoritative engine before persistence. */
public record GameSetup(int playerCount, int cpuCount, List<Player> players,
                        BoardState board, Map<String, Object> options) {
    public GameSetup {
        players = players == null ? List.of() : List.copyOf(players);
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
