package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.metis.engine.core.rules.LanguageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class MechanicalQueriesTest {
    private OperationRegistry registry(Set<OperationRegistry.Effect> effects,OperationRegistry.Preview preview,RuleType result) {
        return new OperationRegistry(List.of(new OperationRegistry.Module("measure","1",List.of(
                OperationRegistry.Operation.mechanical("measure.length",Map.of("entity",RuleType.ENTITY,"scale",RuleType.DECIMAL),result,effects,preview)))));
    }
    private ObjectNode definition() throws Exception {
        var source=example("resource-race");((ObjectNode)source.get("modules")).put("measure","1");
        edit(source,"/actions/collect/availableWhen",JSON.readTree("""
          {"op":"lt","args":[{"query":{"op":"measure.length","args":{"entity":{"var":"counter"},"scale":2}}},10]}
          """),false);return source;
    }
    @Test void pureMechanicalQueriesAreTypedAndHaveImmutableExplicitInputs() throws Exception {
        var result=new RulesCompiler(registry(Set.of(OperationRegistry.Effect.PURE),OperationRegistry.Preview.READ_ONLY,RuleType.DECIMAL)).compile(definition().toString());assertTrue(result.valid(),result.diagnostics().toString());
        var predicate=result.definition().actions().get("collect").availableWhen();var base=context();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var context=new EvaluationContext(base.variables(),base.state(),base.entities(),32,new EvaluationContext.Budget(100),Map.of("measure.length",(args,entities)->{
            calls.incrementAndGet();assertEquals(RuleType.ENTITY,args.get("entity").type());assertEquals(RuleType.DECIMAL,args.get("scale").type());
            assertThrows(UnsupportedOperationException.class,args::clear);assertThrows(UnsupportedOperationException.class,entities::clear);
            return RuleValue.decimal(new BigDecimal("8"));
        }));
        assertEquals(RuleValue.bool(true),Expressions.evaluate(predicate,context));assertEquals(1,calls.get());
        assertTrue(context.bind("extra",RuleValue.integer(1)).queries().containsKey("measure.length"));
        assertEquals("QUERY_UNAVAILABLE",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(predicate,base)).code());
        var wrong=new EvaluationContext(base.variables(),base.state(),base.entities(),32,new EvaluationContext.Budget(100),Map.of("measure.length",(a,e)->RuleValue.bool(false)));
        assertEquals("VALUE_TYPE",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(predicate,wrong)).code());
    }
    @Test void sideEffectsUnknownQueriesAndUnpinnedQueryModulesCannotEnterPredicates() throws Exception {
        for(var effects:List.of(Set.of(OperationRegistry.Effect.STATE),Set.of(OperationRegistry.Effect.RANDOM),Set.of(OperationRegistry.Effect.DECISION))) {
            var result=new RulesCompiler(registry(effects,OperationRegistry.Preview.READ_ONLY,RuleType.DECIMAL)).compile(definition().toString());assertEquals("QUERY_NOT_PURE",result.diagnostics().getFirst().code());
        }
        for(var type:Arrays.asList(RuleType.DECIMAL,null)) {
            var result=new RulesCompiler(registry(Set.of(OperationRegistry.Effect.PURE),OperationRegistry.Preview.NONE,type)).compile(definition().toString());assertEquals("QUERY_NOT_PURE",result.diagnostics().getFirst().code());
        }
        var compiler=new RulesCompiler(registry(Set.of(OperationRegistry.Effect.PURE),OperationRegistry.Preview.PURE,RuleType.DECIMAL));
        var source=definition();((ObjectNode)source.get("modules")).remove("measure");assertEquals("MODULE_NOT_DECLARED",compiler.compile(source.toString()).diagnostics().getFirst().code());
        source=definition();edit(source,"/actions/collect/availableWhen/args/0/query/op",JSON.getNodeFactory().textNode("measure.missing"),false);assertEquals("OPERATION_UNKNOWN",compiler.compile(source.toString()).diagnostics().getFirst().code());
    }
}
