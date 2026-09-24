package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ResolvedModesPlayTest {
    static CompiledRules mode(JsonNode source,String name,JsonNode patches) {
        var base=new RulesCompiler().compile(source.toString());assertTrue(base.valid(),base.diagnostics().toString());
        var mode=JSON.createObjectNode().put("modeVersion",1).put("id",name).put("revision","1");mode.putObject("extends").put("id",base.definition().id()).put("revision",base.definition().revision()).put("fingerprint",base.definition().fingerprint());mode.set("overrides",patches);
        var result=new RulesResolver().resolve(mode.toString(),r->source.toString());assertTrue(result.valid(),result.diagnostics().toString());return result.definition();
    }
    @Test void baseSmallAndCooperativeTeamModesExecuteWithoutRuntimeChanges() throws Exception {
        var source=example("resource-race");
        for(int goal:List.of(1,2,5)) {
            var definition=mode(source,"goal"+goal,JSON.readTree("[{\"op\":\"replace\",\"path\":\"/state/target/initial\",\"value\":"+goal+"}]"));var runtime=new RulesRuntime(definition);var state=start(runtime);
            for(int turn=0;turn<20&&state.control().status()!=RulesState.Status.FINISHED;turn++) { String actor=state.players().get(state.control().flow().playerIndex());state=act(runtime,state,actor,"collect");state=runtime.restore(RulesStateCodec.encode(state)); }
            assertEquals(List.of("alice"),state.control().winners());assertEquals(goal,state.entities().get("alice").attributes().get("score").integer());
        }
        var patch=JSON.readTree("""
          [{"op":"replace","path":"/procedures/finish/steps","value":[
            {"op":"core.if","condition":{"op":"gte","args":[{"op":"sum","args":[{"map":{"source":{"entities":"counter"},"as":"c","value":{"get":{"entity":{"var":"c"},"field":"score"}}}}]},{"state":"target"}]},"then":[{"op":"core.endGame","outcome":"team-victory","winners":{"var":"players"}}],"else":[]}
          ]}]
          """);
        var runtime=new RulesRuntime(mode(source,"cooperative",patch));var state=start(runtime);
        for(int turn=0;turn<5;turn++)state=act(runtime,state,state.players().get(state.control().flow().playerIndex()),"collect");
        assertEquals("team-victory",state.control().outcome());assertEquals(List.of("alice","bob"),state.control().winners());
    }
    static RulesState play(CompiledRules definition,long ticket) {
        var runtime=new RulesRuntime(definition);var tape=new long[100];Arrays.fill(tape,ticket);var first=runtime.initial("catalogue",List.of("alice","bob"),RandomStream.tape(tape));var state=accepted(runtime.start(first,0));var cpu=new RulesCpu();
        for(int n=0;n<80&&state.control().status()!=RulesState.Status.FINISHED;n++) {
            var actor=state.players().get(state.control().flow().playerIndex());var command=cpu.choose(runtime.decisionInput(state,actor)).orElseThrow();state=SpatialConformanceTest.apply(runtime,state,command);state=runtime.restore(RulesStateCodec.encode(state));
        }
        assertEquals(RulesState.Status.FINISHED,state.control().status());return state;
    }
    @Test void catalogueRecipeAndBothModesFinishAndMissesNeverBecomeForcedHits() throws Exception {
        var source=read("/rules/catalogue/armada-squadron-training.json");var pack=read("/rules/catalogue/armada-squadron-training.pack.json");var base=new RulesCompiler().compile(source.toString());assertTrue(base.valid(),base.diagnostics().toString());
        assertEquals("elimination",play(base.definition(),0).control().outcome());
        for(var m:pack.get("modes"))assertEquals("elimination",play(mode(source,m.get("code").textValue(),m.get("overrides")),0).control().outcome());
        var misses=play(base.definition(),6);assertEquals("round-limit-draw",misses.control().outcome());assertTrue(misses.control().winners().isEmpty());assertEquals(3,misses.entities().get("alice").resources().get("health"));assertEquals(5,misses.entities().get("bob").resources().get("health"));
    }
}
