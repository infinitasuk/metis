package com.metis.engine.core;

/** A player seat in an authoritative game state. */
public record Player(long playerId, int seatNumber, String kind, String factionCode) {
    public Player {
        if (playerId < 0) {
            throw new IllegalArgumentException("playerId must not be negative");
        }
        if (seatNumber < 1) {
            throw new IllegalArgumentException("seatNumber must be positive");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
    }
}
