package com.metis.engine.core;

public interface RuntimeStateStore {
    GameState load(long sessionId);

    GameState save(GameState state, long expectedStateVersion);
}
