package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ReactionWindowsTest {
    private RulesState open(RulesRuntime runtime,List<String> players) {
        var initial=runtime.initial("windows",players);var state=accepted(runtime.start(initial,0));
        state=accepted(runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));return choose(runtime,state,0);
    }
    private RulesState respond(RulesRuntime runtime,RulesState state,String choice) {
        state=runtime.restore(RulesStateCodec.encode(state));var decision=state.control().decision();
        return accepted(runtime.respond(state,decision.player(),decision.id(),decision.options().indexOf(RuleValue.text(choice)),state.version()));
    }
    @Test void orderedNestedWindowsResumeOriginalActionExactlyOnce() throws Exception {
        var runtime=runtime(example("reaction-race"));var state=open(runtime,List.of("alice","bob","carol"));
        assertEquals("bob",state.control().decision().player());assertEquals(List.of("respond"),runtime.controls(state,"bob"));
        assertTrue(RulesView.project(runtime.definition(),state,Set.of("alice")).decision().options().isEmpty());
        assertEquals(1,state.entities().get("alice").resources().get("energy"));assertEquals(0,state.entities().get("alice").attributes().get("score").integer());
        state=respond(runtime,state,"guard");assertEquals("alice",state.control().decision().player());
        assertEquals(2,state.control().continuation().frames().stream().filter(f->f.kind()==RulesState.FrameKind.WINDOW).count());
        assertEquals(1,state.entities().get("bob").resources().get("energy"));
        state=respond(runtime,state,"acknowledge");assertEquals("carol",state.control().decision().player());
        String last=state.control().decision().id();state=respond(runtime,state,"$pass");
        assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals(1,state.control().flow().progress().get("alice").uses().get("act"));
        assertEquals(1,state.entities().get("alice").resources().get("energy"));assertEquals(2,state.entities().get("carol").resources().get("energy"));
        assertTrue(state.globals().get("guarded").bool());assertTrue(state.globals().get("acknowledged").bool());
        rejected("NO_DECISION",runtime.respond(state,"carol",last,0,state.version()));
    }
    @Test void emptyAndIneligibleRespondersAutoCompleteWithoutAStuckWindow() throws Exception {
        var source=example("reaction-race");((ObjectNode)source.at("/windows/defense/choices/0")).put("when",false);
        var runtime=runtime(source);var state=open(runtime,List.of("alice","bob"));assertEquals(RulesState.Status.FINISHED,state.control().status());
        runtime=runtime(example("reaction-race"));state=open(runtime,List.of("alice"));assertEquals(RulesState.Status.FINISHED,state.control().status());
    }
    @Test void responsePriorityBoundsAndPoliciesAreValidated() throws Exception {
        var source=example("reaction-race");((ObjectNode)source.at("/windows/defense")).put("allowPass",false);var runtime=runtime(source);
        var state=open(runtime,List.of("alice","bob"));assertEquals(List.of(RuleValue.text("guard")),state.control().decision().options());
        rejected("WRONG_RESPONDER",runtime.respond(state,"alice",state.control().decision().id(),0,state.version()));
        rejected("CHOICE_INVALID",runtime.respond(state,"bob",state.control().decision().id(),1,state.version()));
        ((ObjectNode)source.get("limits")).put("maxWindowDepth",1);assertEquals("WINDOW_DEPTH",new RulesCompiler().compile(source.toString()).diagnostics().getFirst().code());
        source=example("reaction-race");((ObjectNode)source.at("/windows/defense")).put("maxResponders",33);assertEquals("WINDOW_BOUND",new RulesCompiler().compile(source.toString()).diagnostics().getFirst().code());
        source=example("reaction-race");((ArrayNode)source.at("/windows/defense/choices")).add(source.at("/windows/defense/choices/0").deepCopy());assertEquals("REACTION_DUPLICATE",new RulesCompiler().compile(source.toString()).diagnostics().getFirst().code());
        source=example("reaction-race");((ObjectNode)source.at("/windows/defense/choices/0")).put("procedure","collect");
        ((ObjectNode)source.at("/windows/defense/choices/0/args")).set("actor",source.at("/windows/defense/choices/0/args/attacker"));((ObjectNode)source.at("/windows/defense/choices/0/args")).remove("attacker");
        assertEquals("PROCEDURE_RECURSION",new RulesCompiler().compile(source.toString()).diagnostics().getFirst().code());
    }
    @Test void responderBoundsAndSavedWindowIdentityCannotBeBypassed() throws Exception {
        var source=example("reaction-race");((ObjectNode)source.at("/windows/defense")).put("maxResponders",1);var runtime=runtime(source);
        var initial=runtime.initial("bounded",List.of("alice","bob","carol"));var state=accepted(runtime.start(initial,0));state=accepted(runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
        rejected("WINDOW_RESPONDERS",runtime.respond(state,"alice",state.control().decision().id(),0,state.version()));
        var normal=runtime(example("reaction-race"));var waiting=open(normal,List.of("alice","bob"));
        for(String field:List.of("id","visibility")) {
            var encoded=(ObjectNode)JSON.readTree(RulesStateCodec.encode(waiting));((ObjectNode)encoded.at("/control/decision")).put(field,field.equals("id")?"stale":"PUBLIC");
            assertThrows(RuleEvaluationException.class,()->normal.restore(encoded.toString()));
        }
        var encoded=(ObjectNode)JSON.readTree(RulesStateCodec.encode(waiting));var frames=(ArrayNode)encoded.at("/control/continuation/frames");((ObjectNode)frames.get(frames.size()-1)).put("block","defense");
        assertThrows(RuleEvaluationException.class,()->normal.restore(encoded.toString()));
    }
}
