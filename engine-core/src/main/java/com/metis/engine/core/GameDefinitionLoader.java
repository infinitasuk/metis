package com.metis.engine.core;

public interface GameDefinitionLoader {
    GameDefinition load(long gameId, long definitionVersionId);
}
