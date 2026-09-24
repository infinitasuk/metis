package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.MechanicsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DiceMechanicsTest {
    @Test void exactWeightedFacesStableIndicesCancellationAndRerolls() throws Exception {
        var source=base();body(source,"collect","["+roll("[\"number\",\"number\",\"number\"]","PUBLIC")+","+resolve("total")+","+CHOOSE+","+"""
          {"op":"dice.remove","args":{"roll":{"var":"roll"},"indices":{"list":{"elementType":"integer","items":[0]}}}},
          {"op":"core.setState","field":"indices","value":{"query":{"op":"dice.indices","args":{"roll":{"var":"roll"}}}}},
          {"op":"dice.reroll","args":{"roll":{"var":"roll"},"indices":{"list":{"elementType":"integer","items":[1]}},"allowed":{"query":{"op":"dice.indices","args":{"roll":{"var":"roll"}}}},"maximum":1}},
          {"op":"dice.cancel","args":{"roll":{"var":"roll"},"symbols":{"list":{"elementType":"string","items":["hit"]}},"maximum":1}},
          """+resolve("total")+"]");
        var runtime=runtime(source);var state=act(runtime,seeded(runtime,0,1,2,0),"alice","collect");
        assertEquals(13,state.globals().get("result").integer());assertEquals(3,state.control().mechanics().random().position());
        var roll=state.control().mechanics().rolls().values().iterator().next();assertEquals(List.of(0,1,1),roll.dice().stream().map(MechanicsState.Die::face).toList());
        state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),0);assertEquals(5,state.globals().get("result").integer());assertEquals(List.of(RuleValue.integer(1),RuleValue.integer(2)),state.globals().get("indices").items());
        assertEquals(4,state.control().mechanics().random().position());roll=state.control().mechanics().rolls().get(roll.id());assertFalse(roll.active());assertTrue(roll.dice().get(0).removed());assertEquals(1,roll.dice().get(1).rerolls());runtime.validateCheckpoint(state);
    }
    @Test void allMissesStayMissesAndNumericSymbolTablesAreDefinitionOwned() throws Exception {
        var source=base();body(source,"collect","["+roll("[\"symbol\",\"symbol\",\"symbol\",\"symbol\"]","PUBLIC")+","+resolve("total")+"]");
        var runtime=runtime(source);var state=act(runtime,seeded(runtime,0,0,0,0),"alice","collect");assertEquals(0,state.globals().get("result").integer());
        body(source,"collect","["+roll("[\"symbol\"]","PUBLIC")+","+resolve("partial")+"]");runtime=runtime(source);state=act(runtime,seeded(runtime,1),"alice","collect");assertEquals(1,state.globals().get("result").integer());
        ((ObjectNode)source.at("/resultTables/partial")).put("unmapped","ERROR");runtime=runtime(source);state=seeded(runtime,1);
        rejected("SYMBOL_UNMAPPED",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));assertEquals(0,state.control().mechanics().random().position());
    }
    @Test void illegalRerollsAndLatePoolChangesConsumeNeitherResourcesNorRandomness() throws Exception {
        for(var test:List.of(
                new String[]{"{\"op\":\"dice.reroll\",\"args\":{\"roll\":{\"var\":\"roll\"},\"indices\":{\"list\":{\"elementType\":\"integer\",\"items\":[0,0]}},\"allowed\":{\"list\":{\"elementType\":\"integer\",\"items\":[0]}},\"maximum\":2}}","DIE_DUPLICATE"},
                new String[]{"{\"op\":\"dice.reroll\",\"args\":{\"roll\":{\"var\":\"roll\"},\"indices\":{\"list\":{\"elementType\":\"integer\",\"items\":[0]}},\"allowed\":{\"list\":{\"elementType\":\"integer\",\"items\":[]}},\"maximum\":1}}","REROLL_INELIGIBLE"},
                new String[]{"{\"op\":\"dice.remove\",\"args\":{\"roll\":{\"var\":\"roll\"},\"indices\":{\"list\":{\"elementType\":\"integer\",\"items\":[1]}}}}","DIE_INDEX"},
                new String[]{"{\"op\":\"dice.cancel\",\"args\":{\"roll\":{\"var\":\"roll\"},\"symbols\":{\"list\":{\"elementType\":\"string\",\"items\":[\"critical\"]}},\"maximum\":1}}","SYMBOL_UNAVAILABLE"})) {
            var source=base();body(source,"collect","["+roll("[\"number\"]","PUBLIC")+","+CHOOSE+",{\"op\":\"resources.transfer\",\"args\":{\"from\":{\"var\":\"counter\"},\"fromResource\":\"ready\",\"to\":{\"var\":\"counter\"},\"toResource\":\"exhausted\",\"amount\":1}},"+test[0]+"]");
            var runtime=runtime(source);var state=act(runtime,seeded(runtime,0,1),"alice","collect");String before=RulesStateCodec.encode(state);
            rejected(test[1],runtime.respond(state,"alice",state.control().decision().id(),0,state.version()));assertEquals(before,RulesStateCodec.encode(state));assertEquals(1,state.control().mechanics().random().position());
        }
    }
    @Test void rerollLimitsAndRollLifetimeSurviveDecisionsAndNewRuntimeInstances() throws Exception {
        var source=base();String reroll="{\"op\":\"dice.reroll\",\"args\":{\"roll\":{\"var\":\"roll\"},\"indices\":{\"list\":{\"elementType\":\"integer\",\"items\":[0]}},\"allowed\":{\"query\":{\"op\":\"dice.indices\",\"args\":{\"roll\":{\"var\":\"roll\"}}}},\"maximum\":1}}";
        body(source,"collect","["+roll("[\"number\"]","OWNER")+","+reroll+","+CHOOSE+","+reroll+"]");
        var runtime=runtime(source);var state=act(runtime,seeded(runtime,0,1,2),"alice","collect");var restored=runtime(source).restore(RulesStateCodec.encode(state));
        rejected("REROLL_LIMIT",runtime.respond(restored,"alice",restored.control().decision().id(),0,restored.version()));assertEquals(2,restored.control().mechanics().random().position());
        assertTrue(RulesView.project(runtime.definition(),state,Set.of("bob")).mechanics().rolls().isEmpty());
        String view=RulesStateCodec.encode(RulesView.project(runtime.definition(),state,Set.of("alice")));assertFalse(view.contains("tape-v1"));assertFalse(view.contains("random"));assertFalse(view.contains("scope"));
        body(source,"collect","["+roll("[\"number\"]","PUBLIC")+",{\"op\":\"dice.cancel\",\"args\":{\"roll\":{\"var\":\"roll\"},\"symbols\":{\"list\":{\"elementType\":\"string\",\"items\":[]}},\"maximum\":1}},"+reroll+"]");
        runtime=runtime(source);state=seeded(runtime,0,1);rejected("ROLL_TIMING",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
    }
    @Test void fullDuelUsesPrivateOrdersCardsPlayerSelectedRerollsAndDifferentGuardPolicies() throws Exception {
        for(String policy:List.of("halve","cancel")) {
            var source=example("dice-duel");((ObjectNode)source.at("/state/guardPolicy")).put("initial",policy);
            ((ObjectNode)source.at("/entities/fighter/resources/health")).put("initial",10).put("maximum",10);
            var runtime=runtime(source);var state=seeded(runtime,0,0,0,0,0,0,1,1);
            for(String player:List.of("alice","bob"))state=accepted(runtime.act(state,player,"plan",Map.of("fighter",RuleValue.entity("fighter",player),"orders",RuleValue.list(RuleType.STRING,List.of(RuleValue.text("focus"),RuleValue.text("guard")))),state.version()));
            state=accepted(runtime.act(state,"alice","attack",Map.of("fighter",RuleValue.entity("fighter","alice"),"target",RuleValue.entity("fighter","bob")),state.version()));
            state=respond(runtime,state,"$pass"); // Card window; dice now rolled and focus added.
            assertEquals(7,state.control().mechanics().random().position());
            state=respond(runtime,state,"reroll");state=choose(runtime,state,0);assertEquals(8,state.control().mechanics().random().position());
            state=respond(runtime,state,"guard");assertEquals(policy.equals("halve")?7:5,state.entities().get("bob").resources().get("health"));
            assertTrue(state.control().mechanics().effects().isEmpty());assertEquals(0,state.entities().get("bob").resources().get("guardReady"));assertEquals(1,state.entities().get("bob").resources().get("guardExhausted"));
            assertEquals(List.of(RuleValue.text("guard")),state.entities().get("alice").attributes().get("orders").items());runtime.validateCheckpoint(state);
        }
    }
    private RulesState respond(RulesRuntime runtime,RulesState state,String value) { state=runtime.restore(RulesStateCodec.encode(state));var choice=state.control().decision();return accepted(runtime.respond(state,choice.player(),choice.id(),choice.options().indexOf(RuleValue.text(value)),state.version())); }
    @Test void playingDifferentCardTypesUsesTheSameDefinitionControlledWindow() throws Exception {
        for(int shuffle:List.of(0,1)) {
            var runtime=runtime(example("dice-duel"));var state=seeded(runtime,0,shuffle,0,0,0,1,0);
            for(String player:List.of("alice","bob"))state=accepted(runtime.act(state,player,"plan",Map.of("fighter",RuleValue.entity("fighter",player),"orders",RuleValue.list(RuleType.STRING,List.of(RuleValue.text("focus"),RuleValue.text("guard")))),state.version()));
            state=accepted(runtime.act(state,"alice","attack",Map.of("fighter",RuleValue.entity("fighter","alice"),"target",RuleValue.entity("fighter","bob")),state.version()));
            state=respond(runtime,state,"play");state=choose(runtime,state,0);
            assertEquals(shuffle==0?2:1,state.entities().get("alice").resources().get("rerollReady"));
            assertEquals(shuffle==0?1:2,state.control().mechanics().effects().size());
            var discard=RulesView.project(runtime.definition(),state,Set.of("bob")).mechanics().piles().values().stream().filter(p->p.kind()==MechanicsState.PileKind.DISCARD&&!p.cards().isEmpty()).findFirst().orElseThrow();
            assertEquals(shuffle==0?"supply":"focus",discard.cards().getFirst().type());
            state=respond(runtime,state,"$pass");state=respond(runtime,state,"guard");assertEquals(RulesState.Status.FINISHED,state.control().status());
        }
    }
}
