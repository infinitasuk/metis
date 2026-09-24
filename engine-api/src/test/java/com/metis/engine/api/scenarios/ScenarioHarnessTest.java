package com.metis.engine.api.scenarios;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioHarnessTest {
    @TestFactory
    List<DynamicTest> manifestScenarios() throws Exception {
        var manifest = ScenarioHarness.load("manifest.json");
        assertEquals(1, manifest.required("schemaVersion").intValue());
        var scenarios = manifest.required("scenarios");
        assertTrue(scenarios.isArray() && !scenarios.isEmpty(), "Required scenario manifest is empty");
        var ids = new HashSet<String>();
        var tests = new ArrayList<DynamicTest>();
        for (var file : scenarios) {
            var fixture = ScenarioHarness.load(file.textValue());
            assertTrue(ids.add(fixture.required("id").textValue()), "Duplicate scenario ID");
            tests.add(DynamicTest.dynamicTest(fixture.get("id").textValue(), () -> {
                var first = ScenarioHarness.run(fixture);
                assertEquals(first, ScenarioHarness.run(fixture), "Repeated execution must be deterministic");
            }));
        }
        return tests;
    }

    @Test
    void incorrectOracleActuallyFails() throws Exception {
        var fixture = ScenarioHarness.load("authorized-attack.json").deepCopy();
        ((ObjectNode)fixture).put("id", "intentional-oracle-probe");
        ((ObjectNode)fixture.at("/steps/1/expect/state")).put("/units/1/hullPoints", 777);
        var failure = assertThrows(AssertionError.class, () -> ScenarioHarness.run(fixture));
        assertTrue(failure.getMessage().contains("/units/1/hullPoints"));
    }

    @Test
    void unexpectedRandomDrawAndUnknownAdapterFail() throws Exception {
        var fixture = (ObjectNode)ScenarioHarness.load("authorized-attack.json");
        fixture.put("id", "intentional-tape-probe"); fixture.putArray("randomTape");
        assertTrue(assertThrows(AssertionError.class, () -> ScenarioHarness.run(fixture)).getMessage().contains("Random tape exhausted"));
        fixture.put("adapter", "not-implemented");
        assertThrows(AssertionError.class, () -> ScenarioHarness.run(fixture));
    }
}
