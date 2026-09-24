package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class RuntimeFixtures {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode read(String path) throws Exception { try(var stream=RuntimeFixtures.class.getResourceAsStream(path)) { assertNotNull(stream,path);return JSON.readTree(stream); } }
    static ObjectNode example(String name) throws Exception { return (ObjectNode)read("/rules/examples/"+name+".json"); }
    static ObjectNode race() throws Exception { var source=example("resource-race");((ObjectNode)source.at("/state/target")).put("initial",1000);return source; }
    static RulesRuntime runtime(JsonNode source) { var result=new RulesCompiler().compile(source.toString());assertTrue(result.valid(),result.diagnostics().toString());return new RulesRuntime(result.definition()); }
    static RulesState start(RulesRuntime runtime) { var state=runtime.initial("test",List.of("alice","bob"));return accepted(runtime.start(state,state.version())); }
    static RulesState accepted(RulesState.Result result) {
        assertTrue(result.accepted(),result.code()+": "+result.message());assertNotNull(result.commit());
        assertEquals(result.state().control().decision()!=null?"WAITING_FOR_DECISION":result.state().control().status()==RulesState.Status.FINISHED?"GAME_FINISHED":"ACCEPTED",result.code());
        return result.state();
    }
    static void rejected(String code,RulesState.Result result) { assertFalse(result.accepted());assertEquals(code,result.code(),result.message());assertNull(result.state());assertNull(result.commit()); }
    static RulesState act(RulesRuntime runtime,RulesState state,String player,String action) { return accepted(runtime.act(state,player,action,Map.of("counter",RuleValue.entity("counter",player)),state.version())); }
    static RulesState choose(RulesRuntime runtime,RulesState state,int option) { var decision=state.control().decision();return accepted(runtime.respond(state,decision.player(),decision.id(),option,state.version())); }
    static void body(ObjectNode source,String procedure,String json) throws Exception { ((ObjectNode)source.at("/procedures/"+procedure)).set("steps",JSON.readTree(json)); }
    static ObjectNode entityPhase(String controller,int group) throws Exception {
        var source=race();var phase=(ObjectNode)source.at("/flow/phases/turn");phase.put("controller",controller).put("groupSize",group).put("entityParameter","counter").put("next","cleanup");
        phase.set("entities",JSON.readTree("{\"filter\":{\"source\":{\"entities\":\"counter\"},\"as\":\"candidate\",\"where\":{\"op\":\"eq\",\"args\":[{\"owner\":{\"var\":\"candidate\"}},{\"var\":\"actor\"}]}}}"));
        phase.set("steps",JSON.readTree("[{\"id\":\"act\",\"actions\":[\"collect\"],\"minimum\":0,\"maximum\":1}]"));
        ((ObjectNode)source.at("/flow/phases")).set("cleanup",JSON.readTree("{\"controller\":\"MANUAL\",\"groupSize\":1,\"steps\":[],\"next\":\"turn\"}"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)source.at("/procedures/prepare/steps")).add(JSON.readTree("{\"op\":\"core.createEntity\",\"type\":\"counter\",\"id\":\"extra\",\"owner\":{\"op\":\"at\",\"args\":[{\"var\":\"players\"},0]},\"attributes\":{},\"resources\":{},\"as\":\"extra\"}"));
        return source;
    }
}
