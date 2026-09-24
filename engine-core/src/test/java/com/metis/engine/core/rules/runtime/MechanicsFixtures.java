package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;

final class MechanicsFixtures {
    static ObjectNode base() throws Exception {
        var source=race();((ObjectNode)source.get("limits")).put("maxInstructions",5000);
        for(String module:MechanicsRules.MODULES)((ObjectNode)source.get("modules")).put(module,"1");
        source.set("dice",JSON.readTree("""
         {"number":{"maxRerolls":1,"faces":[{"weight":1,"value":1,"symbols":[]},{"weight":2,"value":4,"symbols":["hit"]}]},
          "symbol":{"maxRerolls":2,"faces":[{"weight":1,"value":0,"symbols":[]},{"weight":1,"value":0,"symbols":["hit","critical"]}]}}
         """));
        source.set("resultTables",JSON.readTree("{\"total\":{\"symbols\":{\"hit\":2,\"critical\":3},\"valueMultiplier\":1,\"unmapped\":\"ERROR\"},\"partial\":{\"symbols\":{\"hit\":1},\"valueMultiplier\":0,\"unmapped\":\"IGNORE\"}}"));
        source.set("decks",JSON.readTree("{\"pack\":{\"cards\":[\"a\",\"b\",\"a\"],\"shuffle\":false,\"exhaustion\":\"REJECT\",\"discardVisibility\":\"PUBLIC\"}}"));
        source.set("effects",JSON.readTree("""
         {"action":{"duration":"ACTION","stacking":"ADD","maximumStacks":2,"visibility":"PUBLIC"},
          "activation":{"duration":"ACTIVATION","stacking":"MAX","maximumStacks":2,"visibility":"OWNER"},
          "round":{"duration":"ROUND","stacking":"REPLACE","maximumStacks":1,"visibility":"SERVER"},
          "persistent":{"duration":"PERSISTENT","stacking":"ADD","maximumStacks":2,"visibility":"PUBLIC"}}
         """));
        var state=(ObjectNode)source.get("state");for(String field:List.of("roll","deck","hand","otherHand","effect"))state.set(field,JSON.readTree("{\"type\":\"string\",\"initial\":\"\",\"visibility\":\"PUBLIC\"}"));
        state.set("result",JSON.readTree("{\"type\":\"integer\",\"initial\":0,\"visibility\":\"PUBLIC\"}"));
        state.set("indices",JSON.readTree("{\"type\":{\"list\":\"integer\"},\"initial\":[],\"visibility\":\"PUBLIC\"}"));
        ((ObjectNode)source.at("/entities/counter/attributes")).set("orders",JSON.readTree("{\"type\":{\"list\":\"string\"},\"initial\":[],\"visibility\":\"OWNER\"}"));
        var resources=(ObjectNode)source.at("/entities/counter/resources");resources.set("ready",JSON.readTree("{\"initial\":1,\"minimum\":0,\"maximum\":1,\"visibility\":\"PUBLIC\"}"));resources.set("exhausted",JSON.readTree("{\"initial\":0,\"minimum\":0,\"maximum\":1,\"visibility\":\"PUBLIC\"}"));
        return source;
    }
    static RulesState seeded(RulesRuntime runtime,long... tape) { return accepted(runtime.start(runtime.initial("mechanics",List.of("alice","bob"),RandomStream.tape(tape)),0)); }
    static final String CHOOSE="{\"op\":\"core.choose\",\"player\":{\"var\":\"actor\"},\"options\":{\"list\":{\"elementType\":\"boolean\",\"items\":[true]}},\"as\":\"answer\",\"visibility\":\"OWNER\"}";
    static String roll(String dice,String visibility) {
        return "{\"op\":\"dice.roll\",\"args\":{\"dice\":{\"list\":{\"elementType\":\"string\",\"items\":"+dice+"}},\"owner\":{\"var\":\"actor\"},\"visibility\":\""+visibility+"\"},\"as\":\"roll\"},{\"op\":\"core.setState\",\"field\":\"roll\",\"value\":{\"var\":\"roll\"}}";
    }
    static String resolve(String table) { return "{\"op\":\"core.setState\",\"field\":\"result\",\"value\":{\"query\":{\"op\":\"dice.resolve\",\"args\":{\"roll\":{\"var\":\"roll\"},\"table\":\""+table+"\"}}}}"; }
}
