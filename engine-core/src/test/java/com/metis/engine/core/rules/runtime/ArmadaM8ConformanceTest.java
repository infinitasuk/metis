package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ArmadaM8ConformanceTest {
    private static ObjectNode source() throws Exception {
        return (ObjectNode) read("/rules/catalogue/armada-squadron-training-m8.json");
    }

    private static RulesRuntime runtime(ObjectNode source) {
        var result = new RulesCompiler().compile(source.toString());
        assertTrue(result.valid(), result.diagnostics().toString());
        return new RulesRuntime(result.definition());
    }

    private static RuleValue decimal(double value) {
        return RuleValue.decimal(BigDecimal.valueOf(value));
    }

    private static Map<String,RuleValue> deploy(String entity, String zone, double x) {
        return Map.of("entity",RuleValue.entity("squadron",entity),"zone",RuleValue.text(zone),
                "x",decimal(x),"y",decimal(300),"heading",decimal(0));
    }

    @Test
    void setupValidatesRosterAndRequiresOwnerSpecificDeployment() throws Exception {
        var runtime = runtime(source());
        var initial = runtime.initial("m8",List.of("alice","bob"),RandomStream.seeded("12".repeat(32)));
        var state = accepted(runtime.start(initial,0));

        assertEquals("deployment",state.control().flow().phase());
        assertEquals(List.of("alice"),runtime.eligibleEntities(state,"alice"));
        state = accepted(runtime.activate(state,"alice","alice",state.version()));
        rejected("DEPLOYMENT_OWNER",runtime.act(state,"alice","deploy",deploy("alice","deployment1",800),state.version()));
        var alice = accepted(runtime.act(state,"alice","deploy",deploy("alice","deployment0",200),state.version()));
        assertTrue(alice.entities().get("alice").attributes().get("placed").bool());

        alice = accepted(runtime.activate(alice,"bob","bob",alice.version()));
        rejected("DEPLOYMENT_OWNER",runtime.act(alice,"bob","deploy",deploy("bob","deployment0",200),alice.version()));
        state = accepted(runtime.act(alice,"bob","deploy",deploy("bob","deployment1",800),alice.version()));
        assertEquals("squadrons",state.control().flow().phase());
        assertEquals(List.of("alice"),runtime.eligibleEntities(state,"alice"));
        state = accepted(runtime.activate(state,"alice","alice",state.version()));
        rejected("NOT_ACTIVE_PLAYER",runtime.activate(state,"bob","bob",state.version()));
        state = accepted(runtime.pass(state,"alice",state.version()));
        assertEquals(1,state.control().flow().playerIndex());
        state = accepted(runtime.activate(state,"bob","bob",state.version()));
        assertEquals("bob",state.control().flow().entity());
    }

    @Test
    void rosterFactionPolicyRejectsInvalidDefinitionOwnedSetup() throws Exception {
        var source = source();
        ((ArrayNode)source.at("/scenario/rosters/fleet/factions")).removeAll().add("Unsupported faction");
        var runtime = runtime(source);
        var initial = runtime.initial("invalid-m8",List.of("alice","bob"),RandomStream.seeded("34".repeat(32)));
        rejected("ROSTER_FACTION",runtime.start(initial,0));
        assertTrue(initial.entities().isEmpty());
    }
}
