package com.metis.engine.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BattleSessionCardTest {
    @Test
    void historicalResultsIdentifyWinnerOrDrawWithoutInventingAnOutcome() {
        var empire = new BattleRepository.SessionParticipant(90, 1, "Galactic Empire", "1", "cpu", null);
        var rebels = new BattleRepository.SessionParticipant(91, 2, "Rebel Alliance", "2", "cpu", null);
        var participants = List.of(empire, rebels);

        assertEquals("WINNER: Galactic Empire / CPU",
                BattleRepository.resultLabel("finished", "victory", 90L, participants));
        assertEquals("DRAW",
                BattleRepository.resultLabel("finished", "round_limit_draw", null, participants));
        assertEquals("RESULT NOT RECORDED",
                BattleRepository.resultLabel("finished", "victory", null, participants));
        assertNull(BattleRepository.resultLabel("running", null, null, participants));
    }

    @Test
    void humanResultUsesAssignedControllerName() {
        var human = new BattleRepository.SessionParticipant(42, 1, "Rebel Alliance", "Commander", "human", "Alice");

        assertEquals("WINNER: Rebel Alliance / Alice",
                BattleRepository.resultLabel("completed", "victory", 42L, List.of(human)));
    }
}
