package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class FlowPoliciesTest {
    @TestFactory List<DynamicTest> entityControllersHaveIndependentBudgetsAndUnequalCounts() {
        return List.of("ALTERNATING_ENTITIES","PLAYER_PHASE").stream().map(controller->DynamicTest.dynamicTest(controller,()->{
            var runtime=runtime(entityPhase(controller,2));var state=start(runtime);
            assertEquals(List.of("alice","extra"),runtime.eligibleEntities(state,"alice"));assertTrue(runtime.candidates(state,"alice").isEmpty());
            rejected("ENTITY_REQUIRED",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
            rejected("ENTITY_INELIGIBLE",runtime.activate(state,"alice","bob",state.version()));
            state=accepted(runtime.activate(state,"alice","alice",state.version()));
            rejected("ACTIVATION_IN_PROGRESS",runtime.activate(state,"alice","extra",state.version()));
            rejected("ENTITY_INELIGIBLE",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","extra")),state.version()));
            state=act(runtime,state,"alice","collect");assertEquals(0,state.control().flow().playerIndex());assertNull(state.control().flow().entity());
            rejected("ENTITY_INELIGIBLE",runtime.activate(state,"alice","alice",state.version()));
            state=accepted(runtime.activate(state,"alice","extra",state.version()));
            state=accepted(runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","extra")),state.version()));assertEquals(1,state.control().flow().playerIndex());
            state=accepted(runtime.activate(state,"bob","bob",state.version()));state=act(runtime,state,"bob","collect");
            assertEquals("cleanup",state.control().flow().phase());state=runtime.restore(RulesStateCodec.encode(state));
            state=accepted(runtime.pass(state,"alice",state.version()));assertEquals("cleanup",state.control().flow().phase());
            rejected("NOT_ACTIVE_PLAYER",runtime.pass(state,"alice",state.version()));state=accepted(runtime.pass(state,"bob",state.version()));
            assertEquals(2,state.control().flow().round());assertEquals(List.of("alice","extra"),runtime.eligibleEntities(state,"alice"));
        })).toList();
    }
    @Test void alternatingEntityGroupOneHandsOverAndReturnsToAnUnexhaustedPlayer() throws Exception {
        var runtime=runtime(entityPhase("ALTERNATING_ENTITIES",1));var state=start(runtime);
        state=accepted(runtime.activate(state,"alice","alice",state.version()));state=accepted(runtime.pass(state,"alice",state.version()));assertEquals(1,state.control().flow().playerIndex());
        state=accepted(runtime.activate(state,"bob","bob",state.version()));state=act(runtime,state,"bob","collect");assertEquals(0,state.control().flow().playerIndex());
        state=accepted(runtime.activate(state,"alice","extra",state.version()));state=accepted(runtime.pass(state,"alice",state.version()));assertEquals("cleanup",state.control().flow().phase());
    }
    @Test void aPlayerWithNoEntitiesIsSkippedAndEntityStepsRequireSelection() throws Exception {
        var source=entityPhase("ALTERNATING_ENTITIES",1);
        ((ArrayNode)source.at("/procedures/prepare/steps")).add(JSON.readTree("{\"op\":\"core.removeEntity\",\"entity\":{\"op\":\"at\",\"args\":[{\"filter\":{\"source\":{\"entities\":\"counter\"},\"as\":\"item\",\"where\":{\"op\":\"eq\",\"args\":[{\"owner\":{\"var\":\"item\"}},{\"op\":\"at\",\"args\":[{\"var\":\"players\"},1]}]}}},0]}}"));
        ((ObjectNode)source.at("/flow/phases/turn")).set("steps",JSON.readTree("[{\"id\":\"first\",\"actions\":[\"collect\"],\"minimum\":0,\"maximum\":1},{\"id\":\"second\",\"actions\":[\"collect\"],\"minimum\":0,\"maximum\":1}]"));
        var runtime=runtime(source);var state=start(runtime);rejected("ENTITY_REQUIRED",runtime.advanceStep(state,"alice",state.version()));
        state=accepted(runtime.activate(state,"alice","alice",state.version()));state=accepted(runtime.advanceStep(state,"alice",state.version()));
        state=act(runtime,state,"alice","collect");assertEquals(0,state.control().flow().playerIndex());assertEquals(List.of("extra"),runtime.eligibleEntities(state,"alice"));
        rejected("ENTITY_REQUIRED",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","extra")),state.version()));
    }
    @Test void requiredAndOptionalOrderedStepsAreEnforced() throws Exception {
        var source=race();((ObjectNode)source.at("/flow/phases/turn")).set("steps",JSON.readTree("[{\"id\":\"first\",\"actions\":[\"collect\"],\"minimum\":1,\"maximum\":2},{\"id\":\"second\",\"actions\":[\"boost\"],\"minimum\":1,\"maximum\":1}]"));
        var runtime=runtime(source);var state=start(runtime);
        rejected("STEP_REQUIRED",runtime.pass(state,"alice",state.version()));rejected("STEP_REQUIRED",runtime.advanceStep(state,"alice",state.version()));
        rejected("ACTION_BUDGET",runtime.act(state,"alice","boost",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
        state=act(runtime,state,"alice","collect");assertEquals(0,state.control().flow().playerIndex());
        state=accepted(runtime.advanceStep(state,"alice",state.version()));
        rejected("STEP_UNAVAILABLE",runtime.advanceStep(state,"alice",state.version()));rejected("STEP_REQUIRED",runtime.pass(state,"alice",state.version()));
        state=act(runtime,state,"alice","boost");assertEquals(1,state.control().flow().playerIndex());
        ((ObjectNode)source.at("/flow/phases/turn/steps/0")).put("minimum",0);runtime=runtime(source);state=start(runtime);
        state=act(runtime,state,"alice","boost");assertEquals(1,state.control().flow().playerIndex());
    }
    @Test void simultaneousPrivatePlansCompleteOnlyWhenEveryPlayerHasSubmitted() throws Exception {
        var source=race();((ObjectNode)source.at("/entities/counter/attributes")).set("plan",JSON.readTree("{\"type\":\"string\",\"initial\":\"\",\"visibility\":\"OWNER\"}"));
        ((ObjectNode)source.at("/procedures/collect/parameters")).put("plan","string");
        ((ObjectNode)source.at("/actions/collect/parameters")).put("plan","string");((ObjectNode)source.at("/actions/collect/args")).set("plan",JSON.readTree("{\"var\":\"plan\"}"));
        body(source,"collect","[{\"op\":\"core.setAttribute\",\"entity\":{\"var\":\"counter\"},\"field\":\"plan\",\"value\":{\"var\":\"plan\"}}]");
        var phase=(ObjectNode)source.at("/flow/phases/turn");phase.put("controller","SIMULTANEOUS");((ObjectNode)phase.at("/steps/0")).set("actions",JSON.readTree("[\"collect\"]"));
        var runtime=runtime(source);var state=start(runtime);assertNull(RulesView.project(runtime.definition(),state,Set.of()).activePlayer());
        state=accepted(runtime.act(state,"bob","collect",Map.of("counter",RuleValue.entity("counter","bob"),"plan",RuleValue.text("PRIVATE_B")),state.version()));
        assertEquals(1,state.control().flow().round());assertTrue(runtime.candidates(state,"bob").isEmpty());
        assertFalse(RulesStateCodec.encode(RulesView.project(runtime.definition(),state,Set.of("alice"))).contains("PRIVATE_B"));
        state=accepted(runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice"),"plan",RuleValue.text("PRIVATE_A")),state.version()));assertEquals(2,state.control().flow().round());
    }
    @Test void automaticEntryProcedureCanSuspendAndContinueWithoutRepeatingEffects() throws Exception {
        var source=race();((ObjectNode)source.at("/procedures")).set("entry",JSON.readTree("""
          {"parameters":{},"steps":[{"op":"core.setState","field":"target","value":25},
           {"op":"core.choose","player":{"op":"at","args":[{"var":"players"},0]},"options":{"list":{"elementType":"integer","items":[1]}},"as":"unused","visibility":"OWNER"}]}
          """));
        ((ObjectNode)source.at("/flow/phases")).set("intro",JSON.readTree("{\"controller\":\"AUTOMATIC\",\"groupSize\":1,\"steps\":[],\"onEnter\":\"entry\",\"next\":\"turn\"}"));
        ((ObjectNode)source.get("flow")).put("initial","intro");
        var runtime=runtime(source);var state=start(runtime);assertEquals("intro",state.control().flow().phase());assertEquals(0,state.control().flow().round());
        assertEquals(RulesState.Purpose.ENTRY,state.control().continuation().purpose());state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),0);
        assertEquals("turn",state.control().flow().phase());assertEquals(1,state.control().flow().round());assertEquals(25,state.globals().get("target").integer());
    }
    @Test void emptyEntityCycleIsBoundedAndDoesNotOverflowTheJavaStack() throws Exception {
        var source=entityPhase("ALTERNATING_ENTITIES",1);body(source,"prepare","[]");
        ((ObjectNode)source.at("/flow/phases")).remove("cleanup");((ObjectNode)source.at("/flow/phases/turn")).put("next","turn");
        var runtime=runtime(source);var initial=runtime.initial("empty",List.of("alice","bob"));rejected("EVALUATION_LIMIT",runtime.start(initial,0));assertEquals(0,initial.events().size());
    }
}
