package com.metis.engine.core;

import java.util.SplittableRandom;

/** Production/test implementation of the engine randomness boundary. */
public final class SeededRandomSource implements RandomSource {
    private final SplittableRandom random;

    public SeededRandomSource(long seed) {
        random = new SplittableRandom(seed);
    }

    @Override
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }
}
