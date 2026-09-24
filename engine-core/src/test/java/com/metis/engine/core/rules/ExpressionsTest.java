package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.util.*;

import static com.metis.engine.core.rules.LanguageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpressionsTest {
    @TestFactory List<DynamicTest> independentExpressionVectors() throws Exception {
        var vectors=read("/rules/expression-vectors.json");var tests=new ArrayList<DynamicTest>();var ids=new HashSet<String>();var operators=new HashSet<String>();
        for(var vector:vectors) {
            assertTrue(ids.add(vector.get("id").textValue()));collectOperators(vector.get("expression"),operators);
            tests.add(DynamicTest.dynamicTest(vector.get("id").textValue(),()->{
                var expression=expression(vector.get("expression").toString());
                if(vector.has("error")) {
                    var error=assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(expression,context()));
                    assertEquals(vector.get("error").textValue(),error.code());assertTrue(error.path().startsWith("/procedures/probe/steps/0/value"));
                } else {
                    var actual=Expressions.evaluate(expression,context());assertEquals(vector.get("type").textValue(),actual.type().display());
                    assertJsonValue(vector.get("value"),JSON.readTree(JSON.writeValueAsBytes(unbox(actual))));
                }
            }));
        }
        assertEquals(ExpressionCompiler.OPERATORS,operators,"Every supported operator needs an independent vector");return tests;
    }
    private static Object unbox(RuleValue value) { return value.type().kind()==RuleType.Kind.LIST?value.items().stream().map(ExpressionsTest::unbox).toList():value.value(); }
    private static void assertJsonValue(JsonNode expected,JsonNode actual) {
        if(expected.isNumber() && actual.isNumber())assertEquals(0,expected.decimalValue().compareTo(actual.decimalValue()));
        else if(expected.isArray() && actual.isArray()) { assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++)assertJsonValue(expected.get(i),actual.get(i)); }
        else assertEquals(expected,actual);
    }
    private static void collectOperators(JsonNode node,Set<String> names) {
        if(node.isObject()&&node.has("op"))names.add(node.get("op").textValue());node.forEach(child->collectOperators(child,names));
    }
    @Test void missingContextAndWrongValuesDoNotSilentlyBecomeZero() throws Exception {
        var base=context();
        var empty=new EvaluationContext(Map.of(),Map.of(),Map.of(),32,new EvaluationContext.Budget(100));
        for(String source:List.of("{\"var\":\"n\"}","{\"state\":\"target\"}")) {
            var expression=expression(source);assertEquals("MISSING_VALUE",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(expression,empty)).code());
        }
        var bad=new EvaluationContext(Map.of("n",RuleValue.text("two")),base.state(),base.entities(),32,new EvaluationContext.Budget(100));
        var variable=expression("{\"var\":\"n\"}");assertEquals("VALUE_TYPE",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(variable,bad)).code());
        var missingEntity=new EvaluationContext(base.variables(),base.state(),Map.of(),32,new EvaluationContext.Budget(100));
        var get=expression("{\"get\":{\"entity\":{\"var\":\"counter\"},\"field\":\"score\"}}");
        assertEquals("ENTITY_NOT_FOUND",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(get,missingEntity)).code());
        for(String source:List.of("{\"owner\":{\"var\":\"counter\"}}","{\"resource\":{\"entity\":{\"var\":\"counter\"},\"field\":\"energy\"}}","{\"get\":{\"entity\":{\"var\":\"counter\"},\"field\":\"score\"}}")) {
            var expr=expression(source);
            var incomplete=new EvaluationContext(base.variables(),base.state(),Map.of("a",new EvaluationContext.Entity("counter","a",null,Map.of(),Map.of())),32,new EvaluationContext.Budget(100));
            assertEquals("MISSING_VALUE",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(expr,incomplete)).code());
        }
    }
    @Test void budgetsAndCollectionsAreBoundedEvenForNestedValues() throws Exception {
        var base=context();var expression=expression("{\"op\":\"add\",\"args\":[1,2]}");
        var budget=new EvaluationContext.Budget(1);var limited=new EvaluationContext(base.variables(),base.state(),base.entities(),32,budget);
        assertEquals("EVALUATION_LIMIT",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(expression,limited)).code());assertEquals(0,budget.remaining());
        var tooMany=new EvaluationContext(Map.of("players",RuleValue.list(RuleType.PLAYER,Collections.nCopies(33,RuleValue.player("p")))),Map.of(),Map.of(),32,new EvaluationContext.Budget(100));
        var players=expression("{\"var\":\"players\"}");assertEquals("COLLECTION_LIMIT",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(players,tooMany)).code());
        var atLimit=new EvaluationContext(Map.of("players",RuleValue.list(RuleType.PLAYER,Collections.nCopies(32,RuleValue.player("p")))),Map.of(),Map.of(),32,new EvaluationContext.Budget(100));
        assertEquals(32,Expressions.evaluate(players,atLimit).items().size());
        var nested=RuleValue.list(RuleType.list(RuleType.INTEGER),List.of(RuleValue.list(RuleType.INTEGER,List.of(RuleValue.integer(1),RuleValue.integer(2)))));
        var nestedExpr=new Expression.Variable("nested",nested.type(),"/nested");
        var nestedContext=new EvaluationContext(Map.of("nested",nested),Map.of(),Map.of(),32,new EvaluationContext.Budget(2));
        assertEquals("EVALUATION_LIMIT",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(nestedExpr,nestedContext)).code());
    }
    @Test void selectorsAreScopedAndInvalidOperatorTypesFailCompilation() throws Exception {
        var invalid=List.of(
                "{\"op\":\"add\",\"args\":[1,true]}","{\"op\":\"eq\",\"args\":[1,\"one\"]}",
                "{\"op\":\"sum\",\"args\":[{\"var\":\"players\"}]}","{\"op\":\"count\",\"args\":[2]}",
                "{\"op\":\"id\",\"args\":[2]}","{\"op\":\"at\",\"args\":[{\"var\":\"players\"},false]}",
                "{\"filter\":{\"source\":{\"var\":\"players\"},\"as\":\"actor\",\"where\":true}}",
                "{\"filter\":{\"source\":{\"var\":\"players\"},\"as\":\"p\",\"where\":1}}",
                "{\"list\":{\"elementType\":\"integer\",\"items\":[true]}}", "null", "[]",
                "{\"var\":\"actor\",\"state\":\"target\"}", "{\"op\":\"add\",\"args\":{}}",
                "{\"list\":{\"elementType\":\"unknown\",\"items\":[]}}",
                "{\"list\":{\"elementType\":[\"integer\"],\"items\":[]}}",
                "{\"entities\":\"missing\"}","{\"var\":\"../bad\"}");
        for(String source:invalid) {
            var fixture=example("resource-race");edit(fixture,"/procedures/boost/steps/0/delta",JSON.readTree(source),false);
            var result=new RulesCompiler().compile(fixture.toString());assertFalse(result.valid(),source);assertTrue(result.diagnostics().getFirst().path().startsWith("/procedures/boost/steps/0/delta"));
        }
    }
    @Test void decimalPrecisionCanonicalizationAndStringBoundsAreExplicit() throws Exception {
        assertEquals(new BigDecimal("0.3333333333333333333333333333333333"),Expressions.evaluate(expression("{\"op\":\"div\",\"args\":[1,3]}"),context()).number());
        for(String literal:List.of("1e-128","1e128","1.0000")) {
            var fixture=example("resource-race");((ObjectNode)fixture.get("state")).set("decimal",JSON.readTree("{\"type\":\"decimal\",\"initial\":"+literal+",\"visibility\":\"PUBLIC\"}"));
            var compiled=compile(fixture);var roundtrip=new RulesCompiler().compile(compiled.canonicalJson());assertTrue(roundtrip.valid(),roundtrip.diagnostics().toString());assertEquals(compiled.fingerprint(),roundtrip.definition().fingerprint());
        }
        for(String literal:List.of("1e129","0.12345678901234567890123456789012345","9223372036854775808")) {
            var fixture=example("resource-race");edit(fixture,"/procedures/boost/steps/0/delta",JSON.readTree(literal),false);assertFalse(new RulesCompiler().compile(fixture.toString()).valid());
        }
        String longString=JSON.writeValueAsString("a".repeat(3000));
        var concat=expression("{\"op\":\"concat\",\"args\":["+longString+","+longString+"]}");
        assertEquals("VALUE_BOUNDS",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(concat,context())).code());
        var tooLarge=expression("{\"op\":\"mul\",\"args\":[1e128,10]}");
        assertEquals("VALUE_BOUNDS",assertThrows(RuleEvaluationException.class,()->Expressions.evaluate(tooLarge,context())).code());
    }
    @Test void valueAndContextContractsAreImmutableAndRejectMismatchedTypes() {
        var values=new ArrayList<>(List.of(RuleValue.integer(1)));var list=RuleValue.list(RuleType.DECIMAL,values);values.clear();assertEquals(1,list.items().size());
        assertEquals(RuleType.DECIMAL,list.items().getFirst().type());assertThrows(UnsupportedOperationException.class,()->list.items().clear());
        assertThrows(IllegalArgumentException.class,()->new RuleValue(RuleType.INTEGER,1));
        assertThrows(IllegalArgumentException.class,()->RuleValue.integer(1).as(RuleType.BOOLEAN));
        assertThrows(IllegalArgumentException.class,()->new RuleType(RuleType.Kind.INTEGER,"unexpected",null));
        assertThrows(IllegalArgumentException.class,()->new EvaluationContext.Budget(0));
        assertThrows(IllegalArgumentException.class,()->new EvaluationContext(Map.of(),Map.of(),Map.of(),0,new EvaluationContext.Budget(1)));
        assertThrows(IllegalArgumentException.class,()->new EvaluationContext(Map.of(),Map.of(),Map.of("x",new EvaluationContext.Entity("counter","different",null,Map.of(),Map.of())),1,new EvaluationContext.Budget(1)));
        var base=context();assertThrows(IllegalArgumentException.class,()->new EvaluationContext(base.variables(),base.state(),base.entities(),1,new EvaluationContext.Budget(10)));
        assertThrows(UnsupportedOperationException.class,()->base.state().clear());
        assertEquals(RuleValue.integer(3),base.bind("new",RuleValue.integer(3)).variables().get("new"));assertFalse(base.variables().containsKey("new"));
    }
}
