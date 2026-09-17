package com.metis.engine.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEngineContractTest {
    @Test
    void initialEngineIsUsableThroughThePlainJavaContract() {
        GameEngine engine = new AuthoritativeGameEngine();
        GameDefinition definition = new GameDefinition(
                7, "demo", "Demo", 8, "demo-1", 2, 2, 0, 2);
        GameSetup setup = new GameSetup(2, 2, List.of(
                new Player(1, 1, "cpu", "red"),
                new Player(2, 2, "cpu", "blue")),
                new BoardState("demo-board", 100, 100),
                Map.of("maximumMovementDistance", 10));

        assertTrue(engine.validateSetup(definition, setup).valid());
        GameState state = engine.createInitialState(50, definition, setup);
        List<LegalAction> actions = engine.legalActions(state);

        assertEquals("start_game", actions.getFirst().type());
        assertEquals(8, state.definitionVersionId());
    }
}
