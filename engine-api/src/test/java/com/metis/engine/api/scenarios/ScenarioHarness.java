package com.metis.engine.api.scenarios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Versioned test fixture adapter, not the future production rules interpreter. */
final class ScenarioHarness {
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode load(String file) throws Exception {
        assertTrue(file.matches("[a-z0-9-]+\\.json"), "Invalid fixture name");
        try (var stream = ScenarioHarness.class.getResourceAsStream("/rules-scenarios/" + file)) {
            assertNotNull(stream, "Missing scenario resource: " + file);
            return JSON.readTree(stream);
        }
    }

    static JsonNode run(JsonNode fixture) throws Exception {
        assertEquals(1, fixture.required("schemaVersion").intValue(), "Unknown fixture schema");
        assertEquals("legacy-authoritative", fixture.required("adapter").textValue(), "Unknown scenario adapter");
        String id = fixture.required("id").textValue();
        assertTrue(id.matches("[a-z0-9-]+"));
        assertTrue(fixture.required("requirements").isArray() && !fixture.get("requirements").isEmpty());
        assertTrue(fixture.required("steps").isArray() && !fixture.get("steps").isEmpty(), "Scenario has no steps");
        var clock = Clock.fixed(Instant.parse(fixture.required("logicalTime").textValue()), ZoneOffset.UTC);
        var tape = new TapeRandom(fixture.required("randomTape"));
        var definition = JSON.treeToValue(fixture.required("definition"), GameDefinition.class);
        GameState state = JSON.treeToValue(fixture.required("initialState"), GameState.class);
        var engine = new AuthoritativeGameEngine(tape);
        int index = 0;
        try {
            for (JsonNode step : fixture.get("steps")) {
                String context = id + " step " + index;
                JsonNode before = tree(state);
                int consumedBefore = tape.index;
                var result = engine.applyAction(definition, state, JSON.treeToValue(step.required("action"), Action.class));
                var expected = step.required("expect");
                assertEquals(expected.required("accepted").booleanValue(), result.accepted(), context + ": " + result.message());
                assertEquals(expected.required("code").textValue(), result.code(), context);
                assertEquals(before, tree(state), context + " mutated input state");
                if (result.accepted()) {
                    assertNotNull(result.resultingState(), context);
                    assertEquals(state.stateVersion() + 1, result.resultingState().stateVersion(), context);
                    state = result.resultingState();
                } else {
                    assertNull(result.resultingState(), context);
                    assertEquals(consumedBefore, tape.index, context + " rejection consumed randomness");
                }
                assertEquals(expected.required("randomConsumed").intValue(), tape.index, context);
                JsonNode observed = tree(state);
                var checks = expected.required("state");
                assertTrue(checks.isObject() && !checks.isEmpty(), context + " has no state assertions");
                checks.fields().forEachRemaining(entry -> {
                    assertTrue(entry.getKey().startsWith("/"), "Expected a JSON pointer");
                    assertEquals(entry.getValue(), observed.at(entry.getKey()), context + " " + entry.getKey());
                });
                for (var view : expected.path("views")) {
                    var owned = new HashSet<Long>();
                    view.required("players").forEach(player -> owned.add(player.longValue()));
                    var visibility = definition.turnStructure() == null ? null : definition.turnStructure().visibility();
                    var projected = tree(new StateProjection(visibility, owned, state.units()).state(state));
                    view.required("state").fields().forEachRemaining(entry ->
                            assertEquals(entry.getValue(), projected.at(entry.getKey()), context + " projected " + entry.getKey()));
                    view.path("absent").forEach(pointer -> assertTrue(projected.at(pointer.textValue()).isMissingNode(), context + " leaked " + pointer));
                }
                index++;
            }
            assertEquals(tape.values.size(), tape.index, "Unused random tape values");
            return tree(state);
        } catch (AssertionError | RuntimeException error) {
            var artifact = JSON.createObjectNode();
            artifact.put("id", id).put("step", index).put("logicalTime", clock.instant().toString());
            artifact.put("definitionSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(fixture.get("definition")))));
            artifact.put("error", error.toString()).put("randomConsumed", tape.index);
            artifact.set("fixture", fixture); artifact.set("observedState", tree(state));
            Path directory = Path.of("target", "rules-scenarios");
            Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(id + "-failure.json").toFile(), artifact);
            throw error;
        }
    }

    private static JsonNode tree(Object value) throws Exception {
        // Round-trip gives JSON numbers the same representation as fixture literals.
        return JSON.readTree(JSON.writeValueAsBytes(value));
    }

    private static final class TapeRandom implements RandomSource {
        private final JsonNode values;
        private int index;
        private TapeRandom(JsonNode values) { assertTrue(values.isArray()); this.values = values; }
        public int nextInt(int bound) {
            assertTrue(index < values.size(), "Random tape exhausted; unexpected draw " + index);
            var value = values.get(index++);
            assertTrue(value.isIntegralNumber() && value.canConvertToInt());
            int result = value.intValue();
            assertTrue(result >= 0 && result < bound, "Random value outside requested bound");
            return result;
        }
    }
}
