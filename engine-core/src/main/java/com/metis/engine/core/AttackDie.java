package com.metis.engine.core;

/** A colored die contribution in a weapon profile. */
public record AttackDie(String color, int count) {
    public AttackDie {
        if (color == null || color.isBlank()) {
            throw new IllegalArgumentException("die color is required");
        }
        if (count < 0) {
            throw new IllegalArgumentException("die count must not be negative");
        }
    }
}
