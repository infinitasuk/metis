package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.MechanicsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class MechanicsCheckpointTest {
    @Test void malformedMechanicalStateCannotMasqueradeAsAValidCheckpoint() throws Exception {
        var source=base();body(source,"collect","["+roll("[\"number\"]","PUBLIC")+","+"""
          {"op":"cards.create","args":{"deck":"pack","owner":{"var":"actor"},"visibility":"SERVER"},"as":"deck"},
          {"op":"cards.hand","args":{"owner":{"var":"actor"},"visibility":"OWNER"},"as":"hand"},
          {"op":"cards.draw","args":{"deck":{"var":"deck"},"to":{"var":"hand"},"count":1},"as":"drawn"},
          {"op":"effects.apply","args":{"entity":{"var":"counter"},"key":"action","value":1},"as":"effect"},
          {"op":"resources.once","args":{"entity":{"var":"counter"},"key":"mark","period":"ROUND"}},
          {"op":"queues.commit","args":{"entity":{"var":"counter"},"field":"orders"}},
          """+CHOOSE+"]");
        var runtime=runtime(source);var state=act(runtime,seeded(runtime,1),"alice","collect");var mechanics=state.control().mechanics();runtime.restore(RulesStateCodec.encode(state));
        String roll=mechanics.rolls().keySet().iterator().next(),deck=mechanics.piles().values().stream().filter(p->p.kind()==MechanicsState.PileKind.DECK).findFirst().orElseThrow().id();
        String hand=mechanics.piles().values().stream().filter(p->p.kind()==MechanicsState.PileKind.HAND).findFirst().orElseThrow().id();
        String card=mechanics.cards().keySet().iterator().next(),effect=mechanics.effects().keySet().iterator().next(),use=mechanics.uses().keySet().iterator().next(),queue=mechanics.queues().keySet().iterator().next();
        var cases=List.of(
            new String[]{"random/algorithm","\"unknown\""},new String[]{"random/position","-1"},new String[]{"random/position","2"},new String[]{"nextId","0"},
            new String[]{"rolls/"+roll+"/id","\"wrong\""},new String[]{"rolls/"+roll+"/owner","\"unknown\""},new String[]{"rolls/"+roll+"/scope","\"wrong\""},
            new String[]{"rolls/"+roll+"/dice/0/type","\"unknown\""},new String[]{"rolls/"+roll+"/dice/0/face","-1"},new String[]{"rolls/"+roll+"/dice/0/face","2"},
            new String[]{"rolls/"+roll+"/dice/0/value","99"},new String[]{"rolls/"+roll+"/dice/0/symbols","[]"},new String[]{"rolls/"+roll+"/dice/0/rerolls","-1"},new String[]{"rolls/"+roll+"/dice/0/rerolls","2"},
            new String[]{"rolls/"+roll+"/cancelled","[\"hit\"]"},new String[]{"cards/"+card+"/id","\"wrong\""},new String[]{"cards/"+card+"/deck","\"unknown\""},new String[]{"cards/"+card+"/type","\"unknown\""},
            new String[]{"piles/"+deck+"/id","\"wrong\""},new String[]{"piles/"+deck+"/owner","\"unknown\""},new String[]{"piles/"+deck+"/deck","null"},new String[]{"piles/"+deck+"/definition","\"unknown\""},
            new String[]{"piles/"+deck+"/cards","[]"},new String[]{"piles/"+hand+"/definition","\"pack\""},new String[]{"piles/"+hand+"/deck","\"wrong\""},
            new String[]{"piles/"+deck+":discard/visibility","\"OWNER\""},new String[]{"effects/"+effect+"/entity","\"unknown\""},new String[]{"effects/"+effect+"/key","\"unknown\""},
            new String[]{"effects/"+effect+"/id","\"wrong\""},new String[]{"effects/"+effect+"/owner","\"bob\""},new String[]{"effects/"+effect+"/visibility","\"OWNER\""},new String[]{"effects/"+effect+"/round","99"},
            new String[]{"uses/"+use+"/entity","\"unknown\""},new String[]{"uses/"+use+"/key","\"wrong\""},new String[]{"uses/"+use+"/period","\"unknown\""},new String[]{"uses/"+use+"/round","99"},
            new String[]{"queues/"+queue+"/entity","\"unknown\""},new String[]{"queues/"+queue+"/field","\"score\""},new String[]{"queues/"+queue+"/locked","-1"},new String[]{"queues/"+queue+"/locked","1"}
        );
        for(var test:cases) {
            var encoded=(ObjectNode)JSON.readTree(RulesStateCodec.encode(state));String pointer="/control/mechanics/"+test[0];int slash=pointer.lastIndexOf('/');var parent=encoded.at(pointer.substring(0,slash));String field=pointer.substring(slash+1);
            if(parent instanceof ObjectNode object)object.set(field,JSON.readTree(test[1]));else ((ArrayNode)parent).set(Integer.parseInt(field),JSON.readTree(test[1]));
            var error=assertThrows(RuntimeException.class,()->runtime.restore(encoded.toString()),pointer);assertTrue(error instanceof RuleEvaluationException||error instanceof IllegalArgumentException,error.toString());
        }
    }
    @Test void coreOnlyCapturesKeepTheirOriginalVersionAndJsonShape() throws Exception {
        var core=runtime(race());var state=start(core);assertEquals("rules-runtime-2",state.runtimeVersion());assertEquals("rules-compiler-3",core.definition().compilerVersion());
        assertNull(state.control().mechanics());assertFalse(RulesStateCodec.encode(state).contains("mechanics"));assertEquals(state,core.restore(RulesStateCodec.encode(state)));
        var newRules=runtime(base());assertThrows(RuleEvaluationException.class,()->newRules.restore(RulesStateCodec.encode(state)));
        assertThrows(RuleEvaluationException.class,()->core.initial("old",List.of("alice"),RandomStream.tape(new long[0])));
    }
}
