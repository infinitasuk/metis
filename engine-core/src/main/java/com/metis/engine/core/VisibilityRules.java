package com.metis.engine.core;

import java.util.List;

/** Disclosure configuration; administrative privileges do not change an audience. */
public record VisibilityRules(Audience units, List<String> publicValues, List<String> publicEvents) {
    public enum Audience { PUBLIC, OWNER, SERVER }
    public VisibilityRules {
        units = units == null ? Audience.PUBLIC : units;
        publicValues = publicValues == null ? List.of() : List.copyOf(publicValues);
        publicEvents = publicEvents == null ? List.of() : List.copyOf(publicEvents);
        if (publicValues.stream().anyMatch(key -> List.of("serverOnly", "initialSetupOptions").contains(key))) {
            throw new IllegalArgumentException("Internal state cannot be declared public");
        }
    }
    public static VisibilityRules defaults() { return new VisibilityRules(Audience.PUBLIC, List.of(), List.of()); }
}
