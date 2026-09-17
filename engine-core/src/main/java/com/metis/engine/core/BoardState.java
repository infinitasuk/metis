package com.metis.engine.core;

/** Runtime board geometry used by rules, rendering and coordinate validation. */
public record BoardState(String boardCode, double width, double height) {
    public BoardState {
        if (boardCode == null || boardCode.isBlank()) {
            throw new IllegalArgumentException("boardCode is required");
        }
        if (!(width > 0) || !(height > 0)) {
            throw new IllegalArgumentException("board dimensions must be positive");
        }
    }
}
