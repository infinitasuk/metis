package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.MechanicsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CardEffectMechanicsTest {
    private static final String CREATE="""
       {"op":"cards.create","args":{"deck":"pack","owner":{"var":"actor"},"visibility":"SERVER"},"as":"deck"},
       {"op":"cards.hand","args":{"owner":{"var":"actor"},"visibility":"OWNER"},"as":"hand"},
       {"op":"core.setState","field":"deck","value":{"var":"deck"}},
       {"op":"core.setState","field":"hand","value":{"var":"hand"}}
       """;
    @Test void deckShuffleDrawDiscardReshufflePreserveUniqueCardsAndPrivacy() throws Exception {
        var source=base();((ObjectNode)source.at("/decks/pack")).put("exhaustion","RESHUFFLE");
        body(source,"collect","["+CREATE+","+"""
          {"op":"cards.shuffle","args":{"deck":{"var":"deck"}}},
          {"op":"cards.draw","args":{"deck":{"var":"deck"},"to":{"var":"hand"},"count":2},"as":"drawn"},
          """+CHOOSE+","+"""
          {"op":"cards.discard","args":{"cards":{"var":"drawn"},"from":{"var":"hand"},"deck":{"var":"deck"}}},
          {"op":"cards.draw","args":{"deck":{"var":"deck"},"to":{"var":"hand"},"count":3},"as":"again"}
          ]
          """);
        var runtime=runtime(source);var state=act(runtime,seeded(runtime,2,0,0),"alice","collect");
        String hand=(String)state.globals().get("hand").value(),deck=(String)state.globals().get("deck").value();
        var owner=RulesView.project(runtime.definition(),state,Set.of("alice"));var other=RulesView.project(runtime.definition(),state,Set.of("bob"));
        assertEquals(List.of("b","a"),owner.mechanics().piles().get(hand).cards().stream().map(MechanicsState.Card::type).toList());
        assertFalse(other.mechanics().piles().containsKey(hand));assertFalse(owner.mechanics().piles().containsKey(deck));
        state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),0);assertEquals(3,state.control().mechanics().random().position());
        var cards=state.control().mechanics().piles().get(hand).cards();assertEquals(3,new HashSet<>(cards).size());
        var after=state;assertEquals(List.of("a","a","b"),cards.stream().map(id->after.control().mechanics().cards().get(id).type()).toList());
        assertTrue(state.control().mechanics().piles().get(deck+":discard").cards().isEmpty());runtime.validateCheckpoint(state);
    }
    @Test void exhaustionPoliciesAndMovesCannotDuplicateOrStealUnheldCards() throws Exception {
        for(String policy:List.of("REJECT","PARTIAL")) {
            var source=base();((ObjectNode)source.at("/decks/pack")).put("exhaustion",policy);
            body(source,"collect","["+CREATE+",{\"op\":\"cards.draw\",\"args\":{\"deck\":{\"var\":\"deck\"},\"to\":{\"var\":\"hand\"},\"count\":5},\"as\":\"drawn\"}]");
            var runtime=runtime(source);var before=seeded(runtime);var result=runtime.act(before,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),before.version());
            if(policy.equals("REJECT")) { rejected("DECK_EXHAUSTED",result);assertTrue(before.control().mechanics().cards().isEmpty()); }
            else { var state=accepted(result);assertEquals(3,state.control().mechanics().piles().get((String)state.globals().get("hand").value()).cards().size()); }
        }
        var source=base();body(source,"collect","["+CREATE+","+"""
          {"op":"cards.hand","args":{"owner":{"op":"at","args":[{"var":"players"},1]},"visibility":"OWNER"},"as":"other"},
          {"op":"cards.draw","args":{"deck":{"var":"deck"},"to":{"var":"hand"},"count":1},"as":"drawn"},
          {"op":"cards.move","args":{"cards":{"var":"drawn"},"from":{"var":"hand"},"to":{"var":"other"}}},
          """+CHOOSE+",{\"op\":\"cards.discard\",\"args\":{\"cards\":{\"var\":\"drawn\"},\"from\":{\"var\":\"hand\"},\"deck\":{\"var\":\"deck\"}}}]");
        var runtime=runtime(source);var state=act(runtime,seeded(runtime),"alice","collect");String saved=RulesStateCodec.encode(state);
        rejected("CARD_NOT_HELD",runtime.respond(state,"alice",state.control().decision().id(),0,state.version()));assertEquals(saved,RulesStateCodec.encode(state));runtime.validateCheckpoint(state);
    }
    @Test void resourceConversionAndCombinedCostsAreAtomicAndOnceMarkersExpire() throws Exception {
        var source=base();((ObjectNode)source.at("/flow/phases/turn/steps/0")).put("minimum",0).put("maximum",2);
        body(source,"collect","""
          [{"op":"resources.once","args":{"entity":{"var":"counter"},"key":"order","period":"ACTIVATION"}},
           {"op":"resources.convert","args":{"entity":{"var":"counter"},"from":"energy","to":"exhausted","spend":1,"gain":1}}]
          """);
        var runtime=runtime(source);var state=act(runtime,seeded(runtime),"alice","collect");assertEquals(1,state.entities().get("alice").resources().get("energy"));
        rejected("RESOURCE_ALREADY_USED",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
        assertFalse(state.control().mechanics().uses().isEmpty());state=accepted(runtime.pass(state,"alice",state.version()));assertTrue(state.control().mechanics().uses().isEmpty());
        body(source,"collect","""
          [{"op":"resources.transfer","args":{"from":{"var":"counter"},"fromResource":"ready","to":{"var":"counter"},"toResource":"exhausted","amount":1}},
           {"op":"resources.convert","args":{"entity":{"var":"counter"},"from":"energy","to":"exhausted","spend":1,"gain":1}}]
          """);
        runtime=runtime(source);state=seeded(runtime);rejected("RESOURCE_BOUNDS",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
        assertEquals(1,state.entities().get("alice").resources().get("ready"));assertEquals(2,state.entities().get("alice").resources().get("energy"));
    }
    @Test void actionActivationRoundAndPersistentEffectsHaveDifferentLifetimes() throws Exception {
        var source=base();((ObjectNode)source.at("/flow/phases/turn/steps/0")).put("minimum",0).put("maximum",2);
        body(source,"collect","["+"""
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"action","value":1},"as":"a"},
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"activation","value":2},"as":"b"},
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"activation","value":3},"as":"c"},
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"round","value":2},"as":"d"},
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"round","value":5},"as":"e"},
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"persistent","value":4},"as":"f"},
          {"op":"core.setState","field":"effect","value":{"var":"f"}},
          {"op":"core.setState","field":"result","value":{"query":{"op":"effects.total","args":{"entity":{"var":"counter"},"key":"activation"}}}},
          """+CHOOSE+"]");
        var runtime=runtime(source);var state=act(runtime,seeded(runtime),"alice","collect");assertEquals(3,state.globals().get("result").integer());assertEquals(5,state.control().mechanics().effects().size());
        var other=RulesView.project(runtime.definition(),state,Set.of("bob"));assertEquals(2,other.mechanics().effects().size());
        state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),0);assertEquals(4,state.control().mechanics().effects().size()); // Action expired, activation still has two stacks.
        state=accepted(runtime.pass(state,"alice",state.version()));assertEquals(2,state.control().mechanics().effects().size());
        state=accepted(runtime.pass(state,"bob",state.version()));assertEquals(2,state.control().flow().round());assertEquals(1,state.control().mechanics().effects().size());runtime.validateCheckpoint(state);
        assertEquals("persistent",state.control().mechanics().effects().values().iterator().next().key());
    }
    @Test void queuesKeepCommittedPrefixesAndEnforceRefillCapacity() throws Exception {
        var source=base();body(source,"collect","["+"""
          {"op":"queues.plan","args":{"entity":{"var":"counter"},"field":"orders","capacity":2,"orders":{"list":{"elementType":"string","items":["focus","guard"]}},"allowed":{"list":{"elementType":"string","items":["focus","guard"]}}}},
          {"op":"queues.commit","args":{"entity":{"var":"counter"},"field":"orders"}},
          {"op":"queues.reveal","args":{"entity":{"var":"counter"},"field":"orders"},"as":"revealed"},
          """+CHOOSE+","+"""
          {"op":"queues.plan","args":{"entity":{"var":"counter"},"field":"orders","capacity":2,"orders":{"list":{"elementType":"string","items":["focus"]}},"allowed":{"list":{"elementType":"string","items":["focus","guard"]}}}}
          ]
          """);
        var runtime=runtime(source);var state=act(runtime,seeded(runtime),"alice","collect");assertEquals(List.of(RuleValue.text("guard")),state.entities().get("alice").attributes().get("orders").items());
        state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),0);assertEquals(List.of(RuleValue.text("guard"),RuleValue.text("focus")),state.entities().get("alice").attributes().get("orders").items());
        assertFalse(RulesView.project(runtime.definition(),state,Set.of("bob")).entities().get("alice").attributes().containsKey("orders"));
    }
}
