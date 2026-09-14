package com.metis.engine.core;

import java.util.Map;

/** A client-independent command submitted to the game engine. */
public record Action(String type, long actorPlayerId, Map<String, Object> parameters) {
    public Action {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
