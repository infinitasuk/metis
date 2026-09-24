package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;

import static com.metis.engine.core.rules.LanguageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageConformanceTest {
    @Test void everyCoreOperationHasATypedContractFixture() throws Exception {
        var fixture=example("resource-race");var contract=read("/rules/operation-contract.json");
        fixture.set("windows",JSON.readTree("{\"empty\":{\"parameters\":{},\"responders\":{\"list\":{\"elementType\":\"player\",\"items\":[]}},\"maxResponders\":1,\"allowPass\":true,\"visibility\":\"PUBLIC\",\"choices\":[]}}"));
        ((ObjectNode)fixture.get("procedures")).set("contract",contract);var compiled=compile(fixture);
        var operations=new HashSet<String>();collect(compiled.procedures().get("contract").steps(),operations);
        assertEquals(OperationRegistry.standard().operations().keySet().stream().filter(name->name.startsWith("core.")).collect(java.util.stream.Collectors.toSet()),operations,"Missing registered operation fixture");
        assertEquals(20,compiled.procedures().get("contract").instructionBound());
        assertEquals(RuleType.BOOLEAN,compiled.procedures().get("contract").steps().get(4).inputs().get("options").type().element());
        assertEquals("copy",compiled.procedures().get("contract").steps().get(8).binding());
    }
    private static void collect(List<CompiledRules.Instruction> steps,Set<String> operations) {
        for(var step:steps) { operations.add(step.operation());step.blocks().values().forEach(block->collect(block,operations)); }
    }
    @Test void declarationTypesOwnershipAndPrivateEventsAreGeneric() throws Exception {
        var source=example("resource-race");
        ((ObjectNode)source.get("entities")).set("marker",JSON.readTree("""
          {"ownership":"NONE","attributes":{
            "enabled":{"type":"boolean","initial":true,"visibility":"SERVER"},
            "name":{"type":"string","initial":"beacon","visibility":"PUBLIC"},
            "ratio":{"type":"decimal","initial":1.25,"visibility":"PUBLIC"},
            "ownerRef":{"type":"player","initial":"alice","visibility":"SERVER"},
            "link":{"type":"entity:counter","initial":"a","visibility":"SERVER"},
            "matrix":{"type":{"list":{"list":"decimal"}},"initial":[[1,2.5]],"visibility":"SERVER"}
          },"resources":{"debt":{"initial":-1,"minimum":-10,"maximum":0,"visibility":"SERVER"}}}
          """));
        ((ObjectNode)source.get("events")).set("private",JSON.readTree("{\"visibility\":\"OWNER\",\"fields\":{}}"));
        edit(source,"/procedures/boost/steps",JSON.readTree("""
          [{"op":"core.createEntity","type":"marker","id":"marker","attributes":{"ratio":2},"resources":{"debt":-2},"as":"marker"},
           {"op":"core.emit","event":"private","to":{"var":"actor"},"values":{}},
           {"op":"core.removeEntity","entity":{"var":"marker"}}]
          """),false);
        var compiled=compile(source);assertEquals(CompiledRules.Ownership.NONE,compiled.entities().get("marker").ownership());
        assertEquals(RuleValue.entity("counter","a"),compiled.entities().get("marker").attributes().get("link").initial());
        assertEquals(RuleType.list(RuleType.list(RuleType.DECIMAL)),compiled.entities().get("marker").attributes().get("matrix").type());
        assertThrows(UnsupportedOperationException.class,()->compiled.entities().get("marker").attributes().get("matrix").initial().items().clear());
        edit(source,"/entities/marker/attributes/name/visibility",JSON.getNodeFactory().textNode("OWNER"),false);bad(source,"AUDIENCE_INVALID");
        edit(source,"/entities/marker/attributes/name/visibility",JSON.getNodeFactory().textNode("PUBLIC"),false);
        edit(source,"/procedures/boost/steps/2/entity",JSON.readTree("{\"owner\":{\"var\":\"marker\"}}"),false);bad(source,"OWNER_UNAVAILABLE");
    }
    @Test void instructionArgumentSchemasRejectWrongShapesAndTypes() throws Exception {
        var cases=List.of(
            new String[]{"{\"op\":\"core.let\",\"name\":\"actor\",\"value\":1}","BINDING_EXISTS"},
            new String[]{"{\"op\":\"core.setState\",\"field\":\"unknown\",\"value\":1}","STATE_UNKNOWN"},
            new String[]{"{\"op\":\"core.choose\",\"player\":{\"var\":\"actor\"},\"options\":1,\"as\":\"choice\",\"visibility\":\"OWNER\"}","TYPE_MISMATCH"},
            new String[]{"{\"op\":\"core.removeEntity\",\"entity\":1}","TYPE_MISMATCH"},
            new String[]{"{\"op\":\"core.endGame\",\"outcome\":true,\"winners\":{\"var\":\"players\"}}","TYPE_MISMATCH"},
            new String[]{"{\"op\":\"core.if\",\"condition\":true,\"then\":false,\"else\":[]}","SHAPE_INVALID"},
            new String[]{"{\"op\":\"core.call\",\"procedure\":\"finish\",\"args\":null}","SHAPE_INVALID"},
            new String[]{"{\"op\":\"core.forEach\",\"source\":{\"var\":\"players\"},\"as\":\"p\",\"maxItems\":1.5,\"steps\":[]}","LOOP_BOUND_INVALID"});
        for(var item:cases) {
            var source=example("resource-race");edit(source,"/procedures/boost/steps",JSON.readTree("["+item[0]+"]"),false);bad(source,item[1]);
        }
    }
    @Test void visibilityAndNoResultModuleContractsArePinnedIntoFingerprint() throws Exception {
        var source=example("resource-race");((ObjectNode)source.get("modules")).put("sample","1");
        edit(source,"/procedures/boost/steps",JSON.readTree("[{\"op\":\"sample.signal\",\"args\":{\"value\":2}}]"),false);
        var operation=OperationRegistry.Operation.mechanical("sample.signal",Map.of("value",RuleType.INTEGER),null,Set.of(OperationRegistry.Effect.EVENT),OperationRegistry.Preview.NONE);
        var module=new OperationRegistry.Module("sample","1",List.of(operation));var first=new RulesCompiler(new OperationRegistry(List.of(module))).compile(source.toString());assertTrue(first.valid());
        var changed=OperationRegistry.Operation.mechanical("sample.signal",Map.of("value",RuleType.DECIMAL),null,Set.of(OperationRegistry.Effect.STATE),OperationRegistry.Preview.READ_ONLY);
        var second=new RulesCompiler(new OperationRegistry(List.of(new OperationRegistry.Module("sample","1",List.of(changed))))).compile(source.toString());assertTrue(second.valid());assertNotEquals(first.definition().fingerprint(),second.definition().fingerprint());
        assertThrows(IllegalArgumentException.class,()->new OperationRegistry(List.of(new OperationRegistry.Module("sample","1",List.of(operation,operation)))));
        assertThrows(IllegalArgumentException.class,()->new OperationRegistry.Operation("sample.bad",Set.of("args"),Set.of("args"),Map.of(),null,Set.of(),OperationRegistry.Preview.NONE));
        assertThrows(IllegalArgumentException.class,()->new OperationRegistry.Module("UPPERCASE","",List.of()));
        assertThrows(IllegalArgumentException.class,()->new OperationRegistry(List.of(new OperationRegistry.Module("sample","1",List.of(new OperationRegistry.Operation("sample.bad",Set.of("x"),Set.of(),Map.of(),null,Set.of(),OperationRegistry.Preview.NONE))))));
    }
    @Test void phasePoliciesRejectBadBindingsBudgetsAndEntryProcedures() throws Exception {
        var cases=List.of(
                new String[]{"/flow/initial","\"missing\"","PHASE_UNKNOWN"},
                new String[]{"/flow/roundStart","\"missing\"","PHASE_UNKNOWN"},
                new String[]{"/flow/phases/turn/onEnter","\"boost\"","ARGUMENT_COUNT"},
                new String[]{"/flow/phases/turn/steps","[]","PHASE_EMPTY"});
        for(var item:cases) { var source=example("resource-race");edit(source,item[0],JSON.readTree(item[1]),false);bad(source,item[2]); }
        var source=example("resource-race");var steps=(com.fasterxml.jackson.databind.node.ArrayNode)source.at("/flow/phases/turn/steps");steps.add(steps.get(0).deepCopy());bad(source,"STEP_DUPLICATE");
        source=example("resource-race");edit(source,"/flow/phases/turn/controller",JSON.getNodeFactory().textNode("ALTERNATING_ENTITIES"),false);
        edit(source,"/flow/phases/turn/entities",JSON.readTree("{\"var\":\"players\"}"),false);bad(source,"TYPE_MISMATCH");
        source=example("resource-race");edit(source,"/flow/phases/turn/controller",JSON.getNodeFactory().textNode("MANUAL"),false);edit(source,"/flow/phases/turn/steps",JSON.createArrayNode(),false);compile(source);
        source=example("resource-race");edit(source,"/flow/phases/turn/controller",JSON.getNodeFactory().textNode("SIMULTANEOUS"),false);compile(source);
    }
    @Test void sourceAndLiteralCollectionsCannotExceedDeclaredBounds() throws Exception {
        var source=example("resource-race");var array=JSON.createArrayNode();for(int i=0;i<33;i++)array.add(i);
        var declaration=JSON.createObjectNode();declaration.set("type",JSON.readTree("{\"list\":\"integer\"}"));declaration.set("initial",array);declaration.put("visibility","PUBLIC");
        ((ObjectNode)source.get("state")).set("oversized",declaration);bad(source,"COLLECTION_LIMIT");
        array.remove(32);compile(source);array.add(32);
        source=example("resource-race");var value=JSON.createObjectNode();value.putObject("list").put("elementType","integer").set("items",array);
        edit(source,"/procedures/boost/steps/0/delta",value,false);bad(source,"COLLECTION_LIMIT");
        source=example("resource-race");var procedures=(ObjectNode)source.get("procedures");
        var repeated=JSON.createArrayNode();for(int i=0;i<1000;i++)repeated.add(JSON.readTree("{\"op\":\"core.setState\",\"field\":\"target\",\"value\":1}"));
        for(int i=0;i<11;i++) {var p=procedures.putObject("bulk"+i);p.putObject("parameters");p.set("steps",repeated);}
        bad(source,"SOURCE_LIMIT");
    }
    @Test void diagnosticsIdentifyTheDefinitionAndRuleWithoutInventingAnOwnerForRootErrors() throws Exception {
        var edits=List.of(
            new String[]{"/procedures/boost/steps/0/delta","true","boost"},
            new String[]{"/actions/boost/availableWhen","1","boost"},
            new String[]{"/flow/phases/turn/next","\"missing\"","turn"});
        for(var edit:edits) {
            var source=example("resource-race");LanguageFixtures.edit(source,edit[0],JSON.readTree(edit[1]),false);
            var diagnostic=new RulesCompiler().compile(source.toString()).diagnostics().getFirst();
            assertEquals("resource-race",diagnostic.definitionId());assertEquals(edit[2],diagnostic.ruleId());
        }
        for(String path:List.of("/actions","/procedures","/events","/flow/phases")) {
            var source=example("resource-race");LanguageFixtures.edit(source,path,JSON.createArrayNode(),false);
            var result=new RulesCompiler().compile(source.toString());assertFalse(result.valid());
            assertTrue(result.diagnostics().stream().allMatch(d->d.ruleId()==null));
        }
    }
    @Test void genericMechanicalEntityContractsDoNotDependOnGameTypeNames() throws Exception {
        var op=OperationRegistry.Operation.mechanical("spatial.identity",Map.of("entity",RuleType.ENTITY),RuleType.ENTITY,Set.of(OperationRegistry.Effect.PURE),OperationRegistry.Preview.PURE);
        var compiler=new RulesCompiler(new OperationRegistry(List.of(new OperationRegistry.Module("spatial","1",List.of(op)))));
        var source=example("resource-race");((ObjectNode)source.get("modules")).put("spatial","1");
        edit(source,"/procedures/boost/steps",JSON.readTree("""
          [{"op":"spatial.identity","args":{"entity":{"var":"counter"}},"as":"reference"},
           {"op":"core.let","name":"identity","value":{"op":"id","args":[{"var":"reference"}]}}]
          """),false);
        assertTrue(compiler.compile(source.toString()).valid());
        var entity=RuleValue.entity("counter","a").as(RuleType.ENTITY);assertEquals(new RuleValue.EntityRef("counter","a"),entity.value());
        edit(source,"/procedures/boost/steps/1/value",JSON.readTree("{\"get\":{\"entity\":{\"var\":\"reference\"},\"field\":\"score\"}}"),false);
        assertEquals("ENTITY_TYPE_UNKNOWN",compiler.compile(source.toString()).diagnostics().getFirst().code());
    }
    private static void bad(JsonNode source,String code) { var result=new RulesCompiler().compile(source.toString());assertFalse(result.valid());assertEquals(code,result.diagnostics().getFirst().code(),result.diagnostics().toString()); }
}
