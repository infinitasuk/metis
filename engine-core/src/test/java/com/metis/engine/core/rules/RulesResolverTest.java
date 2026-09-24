package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.LanguageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesResolverTest {
    static ObjectNode mode(String id,CompiledRules base,String patches) throws Exception {
        var root=JSON.createObjectNode().put("modeVersion",1).put("id",id).put("revision","1");
        root.putObject("extends").put("id",base.id()).put("revision",base.revision()).put("fingerprint",base.fingerprint());root.set("overrides",JSON.readTree(patches));return root;
    }
    static RulesResolver.Result resolve(ObjectNode mode,ObjectNode base) { return new RulesResolver().resolve(mode.toString(),r->base.toString()); }
    static void bad(ObjectNode mode,ObjectNode base,String code) { var result=resolve(mode,base);assertFalse(result.valid());assertEquals(code,result.diagnostics().getFirst().code(),result.diagnostics().toString()); }
    @Test void scalarObjectAndArrayOverridesMatchAnIndependentlyAuthoredDefinition() throws Exception {
        var base=example("resource-race");var parent=compile(base);var mode=mode("fast",parent,"""
          [{"op":"replace","path":"/state/target/initial","value":2},
           {"op":"add","path":"/state/label","value":{"type":"string","initial":"fast","visibility":"PUBLIC"}},
           {"op":"replace","path":"/flow/phases/turn/steps","value":[{"id":"act","actions":["collect"],"minimum":1,"maximum":1}]},
           {"op":"remove","path":"/actions/boost"}]
          """);
        var expected=base.deepCopy();expected.put("id","fast");((ObjectNode)expected.at("/state/target")).put("initial",2);
        ((ObjectNode)expected.get("state")).set("label",mode.at("/overrides/1/value").deepCopy());((ObjectNode)expected.at("/flow/phases/turn")).set("steps",mode.at("/overrides/2/value").deepCopy());((ObjectNode)expected.get("actions")).remove("boost");
        var result=resolve(mode,base);assertTrue(result.valid(),result.diagnostics().toString());assertEquals(compile(expected).fingerprint(),result.definition().fingerprint());
        assertEquals("fast",result.resolution().origins().get("/state/target/initial").id());assertEquals(parent.id(),result.resolution().origins().get("/state/target/type").id());assertFalse(result.resolution().origins().keySet().stream().anyMatch(p->p.startsWith("/actions/boost")));
        assertEquals(2,result.resolution().layers().size());assertEquals(4,result.resolution().changes().size());assertEquals(parent,compile(base));
        assertThrows(UnsupportedOperationException.class,()->result.resolution().origins().clear());
    }
    @Test void nestedModesPinExactRevisionsAndRetainPerFieldProvenance() throws Exception {
        var base=example("resource-race");var one=mode("one",compile(base),"[{\"op\":\"replace\",\"path\":\"/state/target/initial\",\"value\":3}]");var first=resolve(one,base);assertTrue(first.valid());
        var two=mode("two",first.definition(),"[{\"op\":\"replace\",\"path\":\"/flow/phases/turn/groupSize\",\"value\":1}]");var sources=Map.of("one",one.toString(),"resource-race",base.toString());
        var result=new RulesResolver().resolve(two.toString(),r->sources.get(r.id()));assertTrue(result.valid());assertEquals(3,result.resolution().layers().size());assertEquals("one",result.resolution().origins().get("/state/target/initial").id());assertEquals("two",result.resolution().origins().get("/flow/phases/turn/groupSize").id());
        ((ObjectNode)base.at("/state/target")).put("initial",99);bad(one,base,"BASE_FINGERPRINT");
        assertEquals(3,result.definition().state().get("target").initial().integer());
    }
    @Test void malformedOverridesFailBeforeExecution() throws Exception {
        var base=example("resource-race");var compiled=compile(base);
        var cases=List.of(new String[]{"[{\"op\":\"merge\",\"path\":\"/state\",\"value\":{}}]","OVERRIDE_OPERATION"},
            new String[]{"[{\"op\":\"replace\",\"path\":\"\",\"value\":{}}]","OVERRIDE_PATH"},
            new String[]{"[{\"op\":\"replace\",\"path\":1,\"value\":0}]","OVERRIDE_PATH"},
            new String[]{"[{\"op\":\"replace\",\"path\":\"/id\",\"value\":\"other\"}]","OVERRIDE_IDENTITY"},
            new String[]{"[{\"op\":\"add\",\"path\":\"/state/target\",\"value\":{}}]","OVERRIDE_TARGET"},
            new String[]{"[{\"op\":\"remove\",\"path\":\"/state/missing\"}]","OVERRIDE_TARGET"},
            new String[]{"[{\"op\":\"replace\",\"path\":\"/missing/value\",\"value\":1}]","OVERRIDE_PATH"},
            new String[]{"[{\"op\":\"replace\",\"path\":\"/flow/phases/turn/steps/0\",\"value\":{}}]","OVERRIDE_PATH"},
            new String[]{"[{\"op\":\"remove\",\"path\":\"/state/~2bad\"}]","OVERRIDE_PATH"},
            new String[]{"[{\"op\":\"remove\",\"path\":\"/state/bad~\"}]","OVERRIDE_PATH"},
            new String[]{"[{\"op\":\"remove\",\"path\":\"/state/target\",\"value\":1}]","MODE_SHAPE"},
            new String[]{"{}","OVERRIDE_LIMIT"},new String[]{"[{\"op\":\"replace\",\"path\":\"/state/target/initial\",\"value\":true}]","VALUE_TYPE"});
        for(var item:cases)bad(mode("mode",compiled,item[0]),base,item[1]);
        var m=mode("mode",compiled,"[]");m.put("modeVersion",2);bad(m,base,"MODE_VERSION");m.put("modeVersion",1).put("extra",true);bad(m,base,"MODE_SHAPE");m.remove("extra");m.put("id","invalid name");bad(m,base,"MODE_ID");
        m=mode("mode",compiled,"[]");((ObjectNode)m.get("extends")).put("fingerprint","latest");bad(m,base,"BASE_REFERENCE");
        m=mode("mode",compiled,"[]");((ObjectNode)m.get("extends")).put("id","another");bad(m,base,"BASE_IDENTITY");
    }
    @Test void cyclesMissingBasesAndSourceLimitsAreExplicit() throws Exception {
        var base=example("resource-race");var m=mode("mode",compile(base),"[]");assertEquals("BASE_NOT_FOUND",new RulesResolver().resolve(m.toString(),r->null).diagnostics().getFirst().code());
        ((ObjectNode)m.get("extends")).put("id","mode");bad(m,base,"MODE_CYCLE");
        var a=mode("a",compile(base),"[]");var b=mode("b",compile(base),"[]");((ObjectNode)a.get("extends")).put("id","b");((ObjectNode)b.get("extends")).put("id","a");assertEquals("MODE_CYCLE",new RulesResolver().resolve(a.toString(),r->b.toString()).diagnostics().getFirst().code());
        assertEquals("SOURCE_LIMIT",new RulesResolver().resolve(null,r->null).diagnostics().getFirst().code());assertEquals("SOURCE_LIMIT",new RulesResolver().resolve(" ".repeat(1_000_001),r->null).diagnostics().getFirst().code());
        for(String source:List.of("[]","null",""))assertEquals("MODE_SHAPE",new RulesResolver().resolve(source,r->null).diagnostics().getFirst().code());
        for(String source:List.of("{","{\"id\":1,\"id\":2}","{} {}"))assertEquals("JSON_SYNTAX",new RulesResolver().resolve(source,r->null).diagnostics().getFirst().code());
        assertEquals("NUMERIC_LIMIT",new RulesResolver().resolve("{\"n\":1e99999999}",r->null).diagnostics().getFirst().code());
        var chain=new HashMap<String,String>();for(int i=0;i<17;i++) { var child=mode("n"+i,compile(base),"[]");((ObjectNode)child.get("extends")).put("id","n"+(i+1));chain.put("n"+i,child.toString()); }assertEquals("RESOLUTION_LIMIT",new RulesResolver().resolve(chain.get("n0"),r->chain.get(r.id())).diagnostics().getFirst().code());
    }
    @Test void plainDefinitionsKeepCompilerFingerprintsAndExactDecimals() throws Exception {
        for(String name:List.of("resource-race","dice-duel","grid-skirmish")) { var source=example(name);var result=resolve(source,source);assertTrue(result.valid());assertEquals(compile(source).fingerprint(),result.definition().fingerprint());assertEquals(1,result.resolution().layers().size()); }
        var source=example("resource-race");((ObjectNode)source.get("state")).set("precise",JSON.readTree("{\"type\":\"decimal\",\"initial\":1.25,\"visibility\":\"PUBLIC\"}"));var parent=compile(source);
        var mode=mode("precise",parent,"[{\"op\":\"replace\",\"path\":\"/state/precise/initial\",\"value\":2.0}]");var result=resolve(mode,source);assertTrue(result.valid());assertTrue(result.definition().canonicalJson().contains("\"initial\":2.0"));
        assertEquals(result,new RulesResolver().resolve(" \n"+mode.toPrettyString(),r->source.toPrettyString()));
    }
}
