package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class LanguageFixtures {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode read(String resource) throws Exception {
        try(var stream=LanguageFixtures.class.getResourceAsStream(resource)) { assertNotNull(stream,resource);return JSON.readTree(stream); }
    }
    static ObjectNode example(String name) throws Exception { return (ObjectNode)read("/rules/examples/"+name+".json"); }
    static CompiledRules compile(JsonNode source) {
        var result=new RulesCompiler().compile(source.toString());assertTrue(result.valid(),()->result.diagnostics().toString());return result.definition();
    }
    static void edit(ObjectNode root,String pointer,JsonNode value,boolean remove) {
        int slash=pointer.lastIndexOf('/');var parent=root.at(pointer.substring(0,slash));String key=pointer.substring(slash+1);
        assertFalse(parent.isMissingNode(),"Patch parent missing: "+pointer);
        if(parent instanceof ObjectNode object) { if(remove)object.remove(key);else object.set(key,value); }
        else if(parent instanceof ArrayNode array) { if(remove)array.remove(Integer.parseInt(key));else array.set(Integer.parseInt(key),value); }
        else fail("Patch parent is not a container: "+pointer);
    }
    static Expression expression(String source) throws Exception {
        var fixture=example("resource-race");
        var probe=JSON.createObjectNode();probe.set("parameters",JSON.readTree("{\"actor\":\"player\",\"counter\":\"entity:counter\",\"n\":\"integer\"}"));
        var instruction=probe.putArray("steps").addObject();instruction.put("op","core.let").put("name","result");instruction.set("value",JSON.readTree(source));
        ((ObjectNode)fixture.get("procedures")).set("probe",probe);
        return compile(fixture).procedures().get("probe").steps().getFirst().inputs().get("value");
    }
    static EvaluationContext context() {
        return new EvaluationContext(Map.of("actor",RuleValue.player("alice"),"counter",RuleValue.entity("counter","a"),"n",RuleValue.integer(2),"round",RuleValue.integer(1),"players",RuleValue.list(RuleType.PLAYER,java.util.List.of(RuleValue.player("alice"),RuleValue.player("bob")))),
                Map.of("target",RuleValue.integer(5),"boostCost",RuleValue.integer(1)),
                Map.of("b",new EvaluationContext.Entity("counter","b","bob",Map.of("score",RuleValue.integer(4)),Map.of("energy",0L)),
                       "a",new EvaluationContext.Entity("counter","a","alice",Map.of("score",RuleValue.integer(3)),Map.of("energy",2L))),32,new EvaluationContext.Budget(1000));
    }
}
