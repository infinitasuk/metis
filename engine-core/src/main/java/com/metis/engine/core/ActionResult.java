package com.metis.engine.core;

/** Outcome returned by the authoritative rules boundary. */
public record ActionResult(boolean accepted, String code, String message, GameState resultingState) {
}
