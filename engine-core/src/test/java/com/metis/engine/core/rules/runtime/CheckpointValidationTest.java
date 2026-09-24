package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointValidationTest {
    private void corrupt(RulesRuntime runtime,RulesState state,String pointer,String json) throws Exception {
        var tree=(ObjectNode)JSON.readTree(RulesStateCodec.encode(state));int slash=pointer.lastIndexOf('/');var parent=tree.at(pointer.substring(0,slash));assertFalse(parent.isMissingNode());String field=pointer.substring(slash+1);
        if(parent instanceof ObjectNode object)object.set(field,JSON.readTree(json));else ((ArrayNode)parent).set(Integer.parseInt(field),JSON.readTree(json));
        var error=assertThrows(RuntimeException.class,()->runtime.restore(tree.toString()),pointer);
        assertTrue(error instanceof RuleEvaluationException||error instanceof IllegalArgumentException,"Unexpected exception for "+pointer+": "+error);
    }
    @Test void malformedWorldCountersProgressAndHistoryAreRejected() throws Exception {
        var runtime=runtime(race());var state=act(runtime,start(runtime),"alice","collect");
        var corruptions=List.of(
            new String[]{"/globals","{}"},new String[]{"/globals/target/value","\"not-integer\""},
            new String[]{"/entities/alice/type","\"missing\""},new String[]{"/entities/alice/id","\"wrong\""},
            new String[]{"/entities/alice/owner","null"},new String[]{"/entities/alice/owner","\"unknown\""},
            new String[]{"/entities/alice/attributes","{}"},new String[]{"/entities/alice/resources","{}"},
            new String[]{"/entities/alice/resources/energy","21"},new String[]{"/control/flow/round","-1"},
            new String[]{"/control/flow/playerIndex","-2"},new String[]{"/control/flow/playerIndex","2"},
            new String[]{"/control/flow/groupCompleted","-1"},new String[]{"/control/nextDecision","0"},
            new String[]{"/control/flow/phase","\"missing\""},new String[]{"/control/flow/completedPlayers","[\"unknown\"]"},
            new String[]{"/control/flow/progress/alice/step","-1"},new String[]{"/control/flow/progress/alice/step","2"},
            new String[]{"/control/flow/progress/alice/uses/act","-1"},new String[]{"/control/flow/progress/alice/uses/act","2"},
            new String[]{"/control/flow/progress/alice/uses","{\"wrong\":1}"},new String[]{"/control/winners","[\"unknown\"]"},
            new String[]{"/control/winners","[\"alice\",\"alice\"]"},new String[]{"/control/status","\"FINISHED\""},
            new String[]{"/events/0/sequence","2"},new String[]{"/runtimeVersion","\"future\""},
            new String[]{"/definitionFingerprint","\"wrong\""},new String[]{"/version","-1"},
            new String[]{"/version","1.5"},new String[]{"/version","\"1\""},new String[]{"/control/flow/round","null"});
        for(var item:corruptions)corrupt(runtime,state,item[0],item[1]);
        var initial=runtime.initial("initial",List.of("alice","bob"));corrupt(runtime,initial,"/control/flow/phase","\"turn\"");
    }
    @Test void pendingStacksCannotLoseTheirScopesOrChangeOriginatingInstructions() throws Exception {
        var runtime=runtime(example("hidden-grid"));var state=start(runtime);assertEquals(3,state.control().continuation().frames().size());
        var corruptions=List.of(
            new String[]{"/control/continuation","null"},new String[]{"/control/decision","null"},
            new String[]{"/control/status","\"FINISHED\""},
            new String[]{"/control/continuation/remainingBudget","-1"},new String[]{"/control/continuation/remainingBudget","1001"},
            new String[]{"/control/continuation/frames","[]"},new String[]{"/control/decision/player","\"unknown\""},
            new String[]{"/control/decision/options","[]"},new String[]{"/control/decision/visibility","\"SERVER\""},
            new String[]{"/control/continuation/frames/0/block","\"missing\""},new String[]{"/control/continuation/frames/0/index","-1"},
            new String[]{"/control/continuation/frames/0/callDepth","0"},new String[]{"/control/continuation/frames/0/callDepth","9"},
            new String[]{"/control/continuation/frames/0/index","99"},new String[]{"/control/continuation/frames/0/binding","\"wrong\""},
            new String[]{"/control/continuation/frames/1/binding","\"wrong\""},new String[]{"/control/continuation/frames/1/index","3"},
            new String[]{"/control/continuation/frames/2/index","0"},new String[]{"/control/continuation/frames/2/locals","{}"},
            new String[]{"/control/decision/binding","\"wrong\""},new String[]{"/control/decision/path","\"wrong\""},
            new String[]{"/control/continuation/frames/2/kind","\"LOOP\""});
        for(var item:corruptions)corrupt(runtime,state,item[0],item[1]);
    }
    @Test void typedCodecPreservesEveryValueKindAndRejectsCoercion() throws Exception {
        var source=race();((ObjectNode)source.get("state")).set("decimal",JSON.readTree("{\"type\":\"decimal\",\"initial\":1.25,\"visibility\":\"SERVER\"}"));
        ((ObjectNode)source.get("state")).set("boolean",JSON.readTree("{\"type\":\"boolean\",\"initial\":true,\"visibility\":\"SERVER\"}"));
        ((ObjectNode)source.get("state")).set("reference",JSON.readTree("{\"type\":\"entity:counter\",\"initial\":\"alice\",\"visibility\":\"SERVER\"}"));
        ((ObjectNode)source.get("state")).set("list",JSON.readTree("{\"type\":{\"list\":\"decimal\"},\"initial\":[1.0,2.5],\"visibility\":\"PUBLIC\"}"));
        var runtime=runtime(source);var state=start(runtime);assertEquals(state,runtime.restore(RulesStateCodec.encode(state)));
        corrupt(runtime,state,"/globals/decimal/value","\"bad\"");corrupt(runtime,state,"/globals/boolean/value","1");corrupt(runtime,state,"/globals/list/value","1");corrupt(runtime,state,"/globals/reference/value/type","\"wrong\"");
        corrupt(runtime,state,"/globals/target/value","null");corrupt(runtime,state,"/globals/target","{\"value\":1}");
        assertThrows(IllegalArgumentException.class,()->RulesStateCodec.decode("{} {}"));assertThrows(IllegalArgumentException.class,()->RulesStateCodec.decode(null));
        for(var type:List.of(RuleType.INTEGER,RuleType.DECIMAL,RuleType.BOOLEAN,RuleType.STRING,RuleType.list(RuleType.INTEGER)))assertThrows(IllegalArgumentException.class,()->RulesStateCodec.literal(type,JSON.readTree("{}")));
        assertEquals(RuleValue.list(RuleType.DECIMAL,List.of(RuleValue.decimal(new java.math.BigDecimal("1.25")))),RulesStateCodec.literal(RuleType.list(RuleType.DECIMAL),JSON.readTree("[1.25]")));
        assertThrows(IllegalArgumentException.class,()->RulesStateCodec.literal(RuleType.INTEGER,JSON.readTree("1.5")));
        assertThrows(IllegalArgumentException.class,()->RulesStateCodec.literal(RuleType.PLAYER,JSON.nullNode()));
        assertEquals(RuleValue.player("alice"),RulesStateCodec.literal(RuleType.PLAYER,JSON.readTree("\"alice\"")));
        assertThrows(IllegalArgumentException.class,()->RulesStateCodec.decode(" ".repeat(16_000_001)));
    }
    @Test void commitsCannotBeAppliedToAnotherSessionOrOutOfOrder() throws Exception {
        var runtime=runtime(race());var initial=runtime.initial("one",List.of("alice","bob"));var result=runtime.start(initial,0);accepted(result);
        var other=runtime.initial("two",List.of("alice","bob"));assertThrows(RuleEvaluationException.class,()->RulesRuntime.replay(other,result.commit()));
        var encoded=(ObjectNode)JSON.readTree(RulesStateCodec.encode(result.commit()));((ObjectNode)encoded.at("/events/0")).put("sequence",7);
        assertThrows(RuleEvaluationException.class,()->RulesRuntime.replay(initial,RulesStateCodec.decodeCommit(encoded.toString())));
        assertThrows(UnsupportedOperationException.class,()->result.state().entities().clear());assertThrows(UnsupportedOperationException.class,()->result.commit().entities().clear());
    }
}
