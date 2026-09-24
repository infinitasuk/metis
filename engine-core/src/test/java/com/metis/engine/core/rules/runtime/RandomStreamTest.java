package com.metis.engine.core.rules.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RandomStreamTest {
    @Test void secureSeedsDifferAndSeededStreamsAreDeterministicAcrossCheckpoints() {
        assertNotEquals(RandomStream.secure().seed(),RandomStream.secure().seed());
        var a=RandomStream.seeded("00".repeat(32));var b=RandomStream.seeded("00".repeat(32));
        for(int bound:new int[]{1,2,6,100,2147483647}) {
            var first=a.draw(bound);var second=b.draw(bound);assertEquals(first,second);assertTrue(first.ticket()>=0&&first.ticket()<bound);assertTrue(first.next().position()>a.position());a=first.next();b=second.next();
        }
    }
    @Test void fixedTapeRejectsUnexpectedOrOutOfRangeDraws() {
        var tape=RandomStream.tape(2,0);assertEquals(2,tape.draw(3).ticket());assertEquals(0,tape.position());
        assertThrows(com.metis.engine.core.rules.RuleEvaluationException.class,()->tape.draw(2));
        var exhausted=tape.draw(3).next().draw(1).next();assertThrows(com.metis.engine.core.rules.RuleEvaluationException.class,()->exhausted.draw(1));
        assertThrows(IllegalArgumentException.class,()->RandomStream.seeded("bad"));assertThrows(IllegalArgumentException.class,()->RandomStream.tape(-1));
        assertThrows(com.metis.engine.core.rules.RuleEvaluationException.class,()->tape.draw(0));
    }
}
