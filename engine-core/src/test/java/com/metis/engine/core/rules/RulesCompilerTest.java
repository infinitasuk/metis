package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.metis.engine.core.rules.LanguageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesCompilerTest {
    @TestFactory List<DynamicTest> invalidDefinitionFixtures() throws Exception {
        var cases=read("/rules/invalid-definitions.json");assertTrue(cases.isArray()&&!cases.isEmpty());var ids=new HashSet<String>();var tests=new ArrayList<DynamicTest>();
        for(var item:cases) {
            String id=item.get("id").textValue();assertTrue(ids.add(id));
            tests.add(DynamicTest.dynamicTest(id,()->{
                var source=example(item.path("example").asText("resource-race"));
                for(var patch:item.get("edits"))edit(source,patch.get("path").textValue(),patch.get("value"),patch.path("remove").asBoolean());
                var result=new RulesCompiler().compile(source.toString());assertFalse(result.valid());assertNull(result.definition());
                assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals(item.get("code").textValue())&&d.path().equals(item.get("path").textValue())),result.diagnostics().toString());
                assertTrue(result.diagnostics().stream().allMatch(d->d.definitionId().equals(source.get("id").textValue())));
            }));
        }
        return tests;
    }
    @Test void completeUnrelatedDefinitionsCompileAndRetainTypedPlans() throws Exception {
        var race=compile(example("resource-race"));var grid=compile(example("hidden-grid"));
        assertEquals(6,race.procedures().get("boost").instructionBound());assertEquals(5,race.procedures().get("prepare").instructionBound());
        assertEquals(10,grid.procedures().get("guess").instructionBound());assertEquals(9,grid.procedures().get("prepare").instructionBound());
        assertEquals(CompiledRules.Audience.OWNER,grid.entities().get("target").attributes().get("cell").visibility());
        assertEquals(RuleType.BOOLEAN,race.actions().get("boost").availableWhen().type());assertNotEquals(race.fingerprint(),grid.fingerprint());
        assertTrue(race.fingerprint().matches("[0-9a-f]{64}"));
    }
    @Test void compiledDefinitionIsDeeplyImmutableAndRoundTripsCanonically() throws Exception {
        var source=example("resource-race");var result=compile(source);var original=result.fingerprint();
        assertThrows(UnsupportedOperationException.class,()->result.actions().clear());
        assertThrows(UnsupportedOperationException.class,()->result.procedures().get("boost").steps().clear());
        assertThrows(UnsupportedOperationException.class,()->result.procedures().get("boost").steps().getFirst().inputs().clear());
        ((ObjectNode)source.at("/state/target")).put("initial",20);
        assertEquals(original,result.fingerprint());assertNotEquals(original,compile(source).fingerprint());
        var second=new RulesCompiler().compile(result.canonicalJson());assertTrue(second.valid());assertEquals(original,second.definition().fingerprint());
    }
    @Test void objectOrderWhitespaceAndDecimalSpellingNormalizeButArrayOrderDoesNot() throws Exception {
        var source=example("resource-race");
        ((ObjectNode)source.get("state")).set("ratio",JSON.readTree("{\"type\":\"decimal\",\"initial\":1.00,\"visibility\":\"PUBLIC\"}"));
        var first=compile(source);
        var shuffled=JSON.createObjectNode();var names=new ArrayList<String>();source.fieldNames().forEachRemaining(names::add);Collections.reverse(names);names.forEach(name->shuffled.set(name,source.get(name)));
        var second=new RulesCompiler().compile(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(shuffled));assertTrue(second.valid());assertEquals(first.fingerprint(),second.definition().fingerprint());
        ((ObjectNode)shuffled.at("/state/ratio")).put("initial",new java.math.BigDecimal("1.0"));assertEquals(first.fingerprint(),compile(shuffled).fingerprint());
        edit(shuffled,"/flow/phases/turn/steps/0/actions",JSON.readTree("[\"boost\",\"collect\"]"),false);assertNotEquals(first.fingerprint(),compile(shuffled).fingerprint());
    }
    @Test void identifiersCarryNoGameOrPhaseSemantics() throws Exception {
        var source=example("resource-race");var original=compile(source);source.put("id","different-game");
        var actions=(ObjectNode)source.get("actions");actions.set("earn",actions.remove("collect"));
        edit(source,"/flow/phases/turn/steps/0/actions",JSON.readTree("[\"earn\",\"boost\"]"),false);
        var flow=(ObjectNode)source.get("flow");flow.put("initial","delta").put("roundStart","delta");
        var phases=(ObjectNode)flow.get("phases");var phase=(ObjectNode)phases.remove("turn");phase.put("next","delta");phases.set("delta",phase);
        var renamed=compile(source);assertEquals(original.actions().get("collect").procedure(),renamed.actions().get("earn").procedure());
        assertEquals(original.procedures(),renamed.procedures());assertEquals(original.flow().phases().get("turn").controller(),renamed.flow().phases().get("delta").controller());
    }
    @Test void syntaxBoundsDuplicateKeysAndTrailingValuesAreRejected() {
        for(String source:List.of("{", "{\"languageVersion\":1,\"languageVersion\":1}","{} {}"))assertEquals("JSON_SYNTAX",new RulesCompiler().compile(source).diagnostics().getFirst().code());
        for(String source:List.of("null","[]",""))assertEquals("SCHEMA_INVALID",new RulesCompiler().compile(source).diagnostics().getFirst().code());
        assertEquals("SOURCE_LIMIT",new RulesCompiler().compile(" ".repeat(1_000_001)).diagnostics().getFirst().code());
        assertEquals("SOURCE_LIMIT",new RulesCompiler().compile(null).diagnostics().getFirst().code());
        assertEquals("JSON_SYNTAX",new RulesCompiler().compile("[".repeat(70)+"0"+"]".repeat(70)).diagnostics().getFirst().code());
    }
    @Test void legalPhaseCyclesAndEntitySelectorsAreValidated() throws Exception {
        var source=example("resource-race");var turn=(ObjectNode)source.at("/flow/phases/turn");
        turn.put("controller","ALTERNATING_ENTITIES");turn.set("entities",JSON.readTree("{\"entities\":\"counter\"}"));turn.put("entityParameter","counter");turn.put("groupSize",2);
        assertEquals(RuleType.list(RuleType.entity("counter")),compile(source).flow().phases().get("turn").entities().type());
        turn.put("controller","PLAYER_PHASE");assertTrue(new RulesCompiler().compile(source.toString()).valid());
        var prefix=JSON.readTree("{\"controller\":\"AUTOMATIC\",\"groupSize\":1,\"steps\":[],\"onEnter\":\"prepare\",\"next\":\"turn\"}");
        ((ObjectNode)source.at("/flow/phases")).set("initial",prefix);((ObjectNode)source.get("flow")).put("initial","initial");compile(source);
        ((ObjectNode)source.get("flow")).put("roundStart","initial");assertCode(source,"ROUND_START_UNREACHABLE");
        ((ObjectNode)source.get("flow")).put("roundStart","turn");((ObjectNode)source.at("/flow/phases")).set("orphan",turn.deepCopy());assertCode(source,"PHASE_UNREACHABLE");
    }
    @Test void crossProcedureRecursionAndBlockScopesCannotEscapeValidation() throws Exception {
        var source=example("resource-race");
        edit(source,"/procedures/finish/steps/1/else",JSON.readTree("[{\"op\":\"core.call\",\"procedure\":\"boost\",\"args\":{\"actor\":{\"var\":\"actor\"},\"counter\":{\"var\":\"counter\"}}}]"),false);
        assertCode(source,"PROCEDURE_RECURSION");
        source=example("resource-race");
        edit(source,"/procedures/boost/steps",JSON.readTree("[{\"op\":\"core.if\",\"condition\":true,\"then\":[{\"op\":\"core.let\",\"name\":\"local\",\"value\":1}],\"else\":[]},{\"op\":\"core.setState\",\"field\":\"target\",\"value\":{\"var\":\"local\"}}]"),false);
        assertCode(source,"BINDING_UNKNOWN");
    }
    @Test void registryPinsMechanicalSignaturesAndRejectsUndeclaredCapabilities() throws Exception {
        var op=OperationRegistry.Operation.mechanical("sample.scale",Map.of("value",RuleType.INTEGER),RuleType.INTEGER,Set.of(OperationRegistry.Effect.PURE),OperationRegistry.Preview.PURE);
        var module=new OperationRegistry.Module("sample","7",List.of(op));var registry=new OperationRegistry(List.of(module));var compiler=new RulesCompiler(registry);
        var source=example("resource-race");((ObjectNode)source.get("modules")).put("sample","7");
        edit(source,"/procedures/boost/steps",JSON.readTree("[{\"op\":\"sample.scale\",\"args\":{\"value\":2},\"as\":\"scaled\"}]"),false);
        var compiled=compiler.compile(source.toString());assertTrue(compiled.valid(),compiled.diagnostics().toString());assertEquals("scaled",compiled.definition().procedures().get("boost").steps().getFirst().binding());
        ((ObjectNode)source.get("modules")).remove("sample");assertEquals("MODULE_NOT_DECLARED",compiler.compile(source.toString()).diagnostics().getFirst().code());
        ((ObjectNode)source.get("modules")).put("sample","7");((ObjectNode)source.get("modules")).remove("core");assertEquals("MODULE_REQUIRED",compiler.compile(source.toString()).diagnostics().getFirst().code());
        assertThrows(IllegalArgumentException.class,()->new OperationRegistry(List.of(module,module)));
        assertThrows(IllegalArgumentException.class,()->new OperationRegistry.Module("wrong","1",List.of(op)));
        assertThrows(UnsupportedOperationException.class,()->registry.operations().clear());
    }
    @Test void cliReportsValidityAndRejectsAnIncorrectDefinition(@TempDir Path directory) throws Exception {
        var file=directory.resolve("definition.json");Files.writeString(file,example("resource-race").toString());
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.PrintStream(bytes);
        assertEquals(0,RulesCompilerCli.run(new String[]{file.toString()},out));assertTrue(bytes.toString().contains("fingerprint"));
        Files.writeString(file,"{}");assertEquals(2,RulesCompilerCli.run(new String[]{file.toString()},out));assertEquals(2,RulesCompilerCli.run(new String[]{},out));
    }
    private static void assertCode(JsonNode source,String code) {
        var result=new RulesCompiler().compile(source.toString());assertFalse(result.valid());assertEquals(code,result.diagnostics().getFirst().code(),result.diagnostics().toString());
    }
}
