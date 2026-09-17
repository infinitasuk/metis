package com.metis.engine.core;

import java.util.List;

/** Stable validation result returned before a setup can be persisted. */
public record SetupValidation(List<Violation> violations) {
    public SetupValidation {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public boolean valid() {
        return violations.isEmpty();
    }

    public record Violation(String code, String message) {}
}
