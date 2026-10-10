package com.metis.engine.core.rules;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RosterValidatorTest {
    private final ScenarioRules.Roster policy=new ScenarioRules.Roster("unit","points",16,1,3,"unique","faction",List.of("blue"));
    private EvaluationContext.Entity unit(String id,long cost,String unique,String faction,String owner) {
        return new EvaluationContext.Entity("unit",id,owner,Map.of("points",RuleValue.integer(cost),"unique",RuleValue.text(unique),"faction",RuleValue.text(faction)),Map.of());
    }
    private EvaluationContext.Entity unit(String id,long cost) { return unit(id,cost,"","blue","alice"); }
    private void rejects(String code,List<EvaluationContext.Entity> members) {
        assertEquals(code,assertThrows(RuleEvaluationException.class,()->RosterValidator.validate(policy,"alice",members)).code());
    }
    @Test void exactBudgetAllowsRepeatedGenericProfilesButNotDuplicateInstancesOrUniqueNames() {
        var units=List.of(unit("first",8),unit("second",8));
        assertEquals(16,RosterValidator.validate(policy,"alice",units));
        rejects("ROSTER_MEMBER",List.of(units.getFirst(),units.getFirst()));
        rejects("ROSTER_UNIQUE",List.of(unit("first",8,"ace","blue","alice"),unit("second",8,"ace","blue","alice")));
        rejects("ROSTER_BUDGET",List.of(unit("first",8),unit("second",9)));
        assertEquals(8,units.getFirst().attributes().get("points").integer());
    }
    @Test void rejectsInvalidMembersCountAndCostsWithoutInventingDefaults() {
        rejects("ROSTER_COUNT",List.of());
        rejects("ROSTER_COUNT",List.of(unit("a",1),unit("b",1),unit("c",1),unit("d",1)));
        rejects("ROSTER_FACTION",List.of(unit("a",8,"","red","alice")));
        rejects("ROSTER_MEMBER",List.of(unit("a",8,"","blue","bob")));
        rejects("ROSTER_MEMBER",Arrays.asList((EvaluationContext.Entity)null));
        rejects("ROSTER_MEMBER",List.of(new EvaluationContext.Entity("other","a","alice",Map.of(),Map.of())));
        rejects("ROSTER_COST",List.of(unit("a",-1)));
        rejects("ROSTER_FIELD",List.of(new EvaluationContext.Entity("unit","a","alice",Map.of(),Map.of())));
        assertThrows(ArithmeticException.class,()->RosterValidator.validate(policy,"alice",List.of(unit("a",Long.MAX_VALUE),unit("b",1))));
    }
    @Test void policiesCanOmitOptionalUniquenessAndFactionBindings() {
        var optional=new ScenarioRules.Roster("unit","points",0,0,1,"","",List.of());
        assertEquals(0,RosterValidator.validate(optional,"alice",List.of(unit("a",0))));
        assertEquals(0,RosterValidator.validate(optional,"alice",List.of()));
    }
}
