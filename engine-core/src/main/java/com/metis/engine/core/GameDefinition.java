package com.metis.engine.core;

import java.util.List;

/** The immutable definition revision used by a simulation. */
public record GameDefinition(long gameId, String gameCode, String gameName,
                             long definitionVersionId, String definitionVersion,
                             int minimumPlayers, int maximumPlayers,
                             int minimumCpu, int maximumCpu,
                             List<String> factionCodes, List<UnitDefinition> unitDefinitions) {
    public GameDefinition(long gameId, String gameCode, String gameName,
                          long definitionVersionId, String definitionVersion,
                          int minimumPlayers, int maximumPlayers,
                          int minimumCpu, int maximumCpu) {
        this(gameId, gameCode, gameName, definitionVersionId, definitionVersion,
                minimumPlayers, maximumPlayers, minimumCpu, maximumCpu, List.of(), List.of());
    }

    public GameDefinition {
        if (gameId < 1 || definitionVersionId < 1) {
            throw new IllegalArgumentException("game identifiers must be positive");
        }
        if (gameCode == null || gameCode.isBlank() || gameName == null || gameName.isBlank()) {
            throw new IllegalArgumentException("game identity is required");
        }
        if (definitionVersion == null || definitionVersion.isBlank()) {
            throw new IllegalArgumentException("definition version is required");
        }
        if (minimumPlayers < 1 || maximumPlayers < minimumPlayers
                || minimumCpu < 0 || maximumCpu < minimumCpu) {
            throw new IllegalArgumentException("invalid player or CPU limits");
        }
        factionCodes = factionCodes == null ? List.of() : List.copyOf(factionCodes);
        unitDefinitions = unitDefinitions == null ? List.of() : List.copyOf(unitDefinitions);
    }
}
