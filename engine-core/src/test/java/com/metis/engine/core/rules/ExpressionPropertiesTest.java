package com.metis.engine.core.rules;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import java.math.BigInteger;
import java.util.List;

import static com.metis.engine.core.rules.LanguageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpressionPropertiesTest {
    @Property(tries=100,seed="20260920")
    void integerArithmeticAgreesWithIndependentBigIntegerOracle(@ForAll @IntRange(min=-100000,max=100000) int a,
                                                               @ForAll @IntRange(min=-100000,max=100000) int b) throws Exception {
        var result=Expressions.evaluate(expression("{\"op\":\"mul\",\"args\":[{\"op\":\"add\",\"args\":["+a+","+b+"]},3]}"),context());
        assertEquals(BigInteger.valueOf(a).add(BigInteger.valueOf(b)).multiply(BigInteger.valueOf(3)).longValueExact(),result.integer());
    }
    @Property(tries=100,seed="20260921")
    void listAggregationAgreesWithIndependentOracle(@ForAll @Size(max=12) List<@IntRange(min=-1000,max=1000) Integer> values) throws Exception {
        String json=JSON.writeValueAsString(values);
        var result=Expressions.evaluate(expression("{\"op\":\"sum\",\"args\":[{\"list\":{\"elementType\":\"integer\",\"items\":"+json+"}}]}"),context());
        assertEquals(values.stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO,BigInteger::add).longValueExact(),result.integer());
    }
}
