package com.metis.engine.core;

import java.util.List;

/** Infrastructure-free contract for validating and advancing a game. */
public interface GameEngine {
    SetupValidation validateSetup(GameDefinition definition, GameSetup setup);

    GameState createInitialState(long sessionId, GameDefinition definition, GameSetup setup);

    List<LegalAction> legalActions(GameState state);

    default List<LegalAction> legalActions(GameDefinition definition, GameState state) {
        return legalActions(state);
    }

    ActionResult applyAction(GameState state, Action action);

    ActionResult applyAction(GameDefinition definition, GameState state, Action action);
}
