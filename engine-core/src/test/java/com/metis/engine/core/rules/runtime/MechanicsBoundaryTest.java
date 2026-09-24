package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.MechanicsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class MechanicsBoundaryTest {
    @TestFactory List<DynamicTest> mechanicalGuardsRejectInvalidOperationsAtomically() {
        var cases=List.of(
            new String[]{"dice.roll","{\"dice\":{\"list\":{\"elementType\":\"string\",\"items\":[\"unknown\"]}},\"owner\":{\"var\":\"actor\"},\"visibility\":\"PUBLIC\"}","DIE_UNKNOWN","newRoll"},
            new String[]{"dice.reroll","{\"roll\":{\"var\":\"roll\"},\"indices\":{\"list\":{\"elementType\":\"integer\",\"items\":[0]}},\"allowed\":{\"list\":{\"elementType\":\"integer\",\"items\":[0]}},\"maximum\":0}","REROLL_LIMIT",null},
            new String[]{"dice.cancel","{\"roll\":{\"var\":\"roll\"},\"symbols\":{\"list\":{\"elementType\":\"string\",\"items\":[\"hit\"]}},\"maximum\":0}","CANCELLATION_LIMIT",null},
            new String[]{"dice.clear","{\"roll\":{\"var\":\"roll\"}}","ROLL_ACTIVE",null},
            new String[]{"dice.close","{\"roll\":\"missing\"}","ROLL_UNKNOWN",null},
            new String[]{"dice.resolve","{\"roll\":{\"var\":\"roll\"},\"table\":\"missing\"}","RESULT_TABLE_UNKNOWN","resolved"},
            new String[]{"cards.create","{\"deck\":\"unknown\",\"owner\":{\"var\":\"actor\"},\"visibility\":\"OWNER\"}","DECK_UNKNOWN","deck"},
            new String[]{"cards.pile","{\"pile\":\"missing\"}","PILE_UNKNOWN","pile"},
            new String[]{"cards.type","{\"card\":\"missing\"}","CARD_UNKNOWN","card"},
            new String[]{"resources.reset","{\"entity\":{\"var\":\"counter\"},\"resource\":\"unknown\",\"value\":0}","RESOURCE_UNKNOWN",null},
            new String[]{"resources.reset","{\"entity\":{\"var\":\"counter\"},\"resource\":\"energy\",\"value\":21}","RESOURCE_BOUNDS",null},
            new String[]{"resources.transfer","{\"from\":{\"var\":\"counter\"},\"fromResource\":\"energy\",\"to\":{\"var\":\"counter\"},\"toResource\":\"energy\",\"amount\":1}","RESOURCE_TRANSFER",null},
            new String[]{"resources.convert","{\"entity\":{\"var\":\"counter\"},\"from\":\"energy\",\"to\":\"ready\",\"spend\":0,\"gain\":1}","RESOURCE_CONVERSION",null},
            new String[]{"resources.once","{\"entity\":{\"var\":\"counter\"},\"key\":\"marker\",\"period\":\"forever\"}","PERIOD_INVALID",null},
            new String[]{"effects.apply","{\"entity\":{\"var\":\"counter\"},\"key\":\"unknown\",\"value\":1}","EFFECT_UNKNOWN","effect"},
            new String[]{"effects.remove","{\"id\":\"missing\"}","EFFECT_UNKNOWN",null},
            new String[]{"queues.commit","{\"entity\":{\"var\":\"counter\"},\"field\":\"score\"}","QUEUE_FIELD",null},
            new String[]{"queues.reveal","{\"entity\":{\"var\":\"counter\"},\"field\":\"orders\"}","QUEUE_EMPTY","order"},
            new String[]{"queues.plan","{\"entity\":{\"var\":\"counter\"},\"field\":\"orders\",\"capacity\":2,\"orders\":{\"list\":{\"elementType\":\"string\",\"items\":[\"x\"]}},\"allowed\":{\"list\":{\"elementType\":\"string\",\"items\":[\"x\"]}}}","QUEUE_CAPACITY",null},
            new String[]{"queues.plan","{\"entity\":{\"var\":\"counter\"},\"field\":\"orders\",\"capacity\":1,\"orders\":{\"list\":{\"elementType\":\"string\",\"items\":[\"x\"]}},\"allowed\":{\"list\":{\"elementType\":\"string\",\"items\":[\"y\"]}}}","ORDER_UNKNOWN",null}
        );
        return cases.stream().map(test->DynamicTest.dynamicTest(test[0]+" / "+test[2],()->{
            var source=base();var instruction=JSON.createObjectNode().put("op",test[0]);instruction.set("args",JSON.readTree(test[1]));if(test[3]!=null)instruction.put("as",test[3]);
            body(source,"collect","["+roll("[\"number\"]","PUBLIC")+","+CHOOSE+","+instruction+"]");
            var runtime=runtime(source);var state=act(runtime,seeded(runtime,1,0),"alice","collect");String saved=RulesStateCodec.encode(state);
            rejected(test[2],runtime.respond(state,"alice",state.control().decision().id(),0,state.version()));assertEquals(saved,RulesStateCodec.encode(state));
        })).toList();
    }
    @Test void mechanicalQueryResultsCleanupAndOncePeriodsAreExplicit() throws Exception {
        var source=base();((ObjectNode)source.get("state")).set("strings",JSON.readTree("{\"type\":{\"list\":\"string\"},\"initial\":[],\"visibility\":\"PUBLIC\"}"));
        ((ObjectNode)source.get("state")).set("flag",JSON.readTree("{\"type\":\"boolean\",\"initial\":false,\"visibility\":\"PUBLIC\"}"));
        body(source,"collect","["+roll("[\"number\"]","PUBLIC")+","+"""
          {"op":"core.setState","field":"result","value":{"query":{"op":"dice.value","args":{"roll":{"var":"roll"},"index":0}}}},
          {"op":"core.setState","field":"deck","value":{"query":{"op":"dice.type","args":{"roll":{"var":"roll"},"index":0}}}},
          {"op":"core.setState","field":"strings","value":{"query":{"op":"dice.symbolsAt","args":{"roll":{"var":"roll"},"index":0}}}},
          {"op":"dice.close","args":{"roll":{"var":"roll"}}},{"op":"dice.clear","args":{"roll":{"var":"roll"}}},
          {"op":"resources.reset","args":{"entity":{"var":"counter"},"resource":"energy","value":4}},
          {"op":"resources.once","args":{"entity":{"var":"counter"},"key":"once","period":"ACTION"}},
          {"op":"core.setState","field":"flag","value":{"query":{"op":"resources.used","args":{"entity":{"var":"counter"},"key":"once","period":"ACTION"}}}},
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"persistent","value":1},"as":"effect"},
          {"op":"effects.ids","args":{"entity":{"var":"counter"},"key":"persistent"},"as":"ids"},
          {"op":"effects.remove","args":{"id":{"var":"effect"}}}
          ]
          """);
        var runtime=runtime(source);var state=act(runtime,seeded(runtime,1),"alice","collect");assertEquals(4,state.globals().get("result").integer());assertEquals("number",state.globals().get("deck").value());
        assertEquals(List.of(RuleValue.text("hit")),state.globals().get("strings").items());assertTrue(state.globals().get("flag").bool());assertEquals(4,state.entities().get("alice").resources().get("energy"));
        assertTrue(state.control().mechanics().rolls().isEmpty());assertTrue(state.control().mechanics().effects().isEmpty());assertTrue(state.control().mechanics().uses().isEmpty());
    }
    @Test void emptyPoolsDoNotInventHitsAndClosedPoolsCannotBeModified() throws Exception {
        var source=base();body(source,"collect","["+roll("[]","PUBLIC")+","+resolve("total")+"]");var runtime=runtime(source);var state=act(runtime,seeded(runtime),"alice","collect");assertEquals(0,state.globals().get("result").integer());assertEquals(0,state.control().mechanics().random().position());
        body(source,"collect","["+roll("[\"number\"]","PUBLIC")+",{\"op\":\"dice.close\",\"args\":{\"roll\":{\"var\":\"roll\"}}},{\"op\":\"dice.add\",\"args\":{\"roll\":{\"var\":\"roll\"},\"dice\":{\"list\":{\"elementType\":\"string\",\"items\":[\"number\"]}}}}]");runtime=runtime(source);state=seeded(runtime,0,1);rejected("ROLL_CLOSED",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));assertEquals(0,state.control().mechanics().random().position());
    }
}
