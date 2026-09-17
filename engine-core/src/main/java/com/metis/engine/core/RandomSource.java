package com.metis.engine.core;

/** Randomness boundary so rules can be deterministic under a supplied seed. */
public interface RandomSource {
    int nextInt(int bound);
}
