package com.metis.engine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.GameDefinition;
import com.metis.engine.core.TurnStructure;
import com.metis.engine.core.UnitDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefinitionSnapshotTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private GameDefinition definition(int health) {
        return new GameDefinition(1, "custom", "Custom", 4, "v1", 2, 2, 0, 2, List.of("a"),
                List.of(new UnitDefinition("unit", "a", 3, 100, 1, health)),
                new TurnStructure(false, List.of(new TurnStructure.Phase("shoot", "Shoot", TurnStructure.Mode.PLAYER_PHASE,
                        List.of(), 1, List.of(new TurnStructure.Step("attack", "Attack", List.of("attack_unit"), 0, 1, false, null)), false))));
    }
    @Test
    void keepsOriginalProfilesAndRulesWhenCatalogueChanges() {
        var snapshot = DefinitionSnapshot.capture(mapper, definition(11));
        assertEquals(11, snapshot.resolve(mapper, 4).unitDefinitions().getFirst().maximumHullPoints());
        assertEquals(4, definition(4).unitDefinitions().getFirst().maximumHullPoints());
        assertEquals(definition(11), snapshot.resolve(mapper, 4));
    }
    @Test
    void rejectsUnsupportedCorruptAndMismatchedSnapshots() {
        var snapshot = DefinitionSnapshot.capture(mapper, definition(11));
        assertThrows(IllegalStateException.class, () -> snapshot.resolve(mapper, 5));
        assertThrows(IllegalStateException.class, () -> new DefinitionSnapshot(99, snapshot.rulesVersion(), snapshot.json(), snapshot.checksum()).resolve(mapper, 4));
        assertThrows(IllegalStateException.class, () -> new DefinitionSnapshot(1, "future-runtime", snapshot.json(), snapshot.checksum()).resolve(mapper, 4));
        assertThrows(IllegalStateException.class, () -> new DefinitionSnapshot(1, snapshot.rulesVersion(), snapshot.json() + " ", snapshot.checksum()).resolve(mapper, 4));
    }
}
