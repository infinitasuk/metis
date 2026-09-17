package com.metis.engine.core;

import java.util.Map;

/** Append-only result of an accepted authoritative transition. */
public record GameEvent(long sequence, String type, Long actorPlayerId,
                        Map<String, Object> payload) {
    public GameEvent {
        if (sequence < 1) {
            throw new IllegalArgumentException("event sequence must be positive");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type is required");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
