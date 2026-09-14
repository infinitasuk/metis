package com.metis.engine.core;

/** The immutable definition revision used by a simulation. */
public record GameDefinition(long gameId, String gameCode, String gameName,
                             long definitionVersionId, String definitionVersion) {
}
