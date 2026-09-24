package com.metis.engine.core;

import java.util.Map;

/** A command shape currently permitted by the authoritative engine. */
public record LegalAction(String type, long actorPlayerId, String description,
                          Map<String, String> requiredParameters, Map<String, Object> parameters) {
    public LegalAction(String type, long actorPlayerId, String description, Map<String, String> requiredParameters) {
        this(type, actorPlayerId, description, requiredParameters, Map.of());
    }
    public LegalAction {
        if (type == null || type.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("legal action type and description are required");
        }
        requiredParameters = requiredParameters == null ? Map.of() : Map.copyOf(requiredParameters);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
