package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.SpatialBoundaryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioModuleTest {
    @Test void damageConsumesAbsorbersBeforeHealthAndUsesExplicitZeroPolicy() throws Exception {
        for(String zero:List.of("KEEP","MARK","REMOVE")) {
            var source=example("vector-orders");((ObjectNode)source.at("/scenario/damage/hull")).put("onZero",zero);var runtime=runtime(source);var state=start(runtime);var m=new RuntimeMachine(runtime,state,false);
            var args=new TreeMap<>(Map.of("target",RuleValue.entity("ship","bob"),"policy",RuleValue.text("hull"),"amount",RuleValue.integer(1)));
            assertEquals(0,m.scenario.execute("scenario.damage",args).integer());assertEquals(0,m.entities.get("bob").resources().get("shield"));assertEquals(4,m.entities.get("bob").resources().get("health"));
            args.put("amount",RuleValue.integer(2));assertEquals(2,m.scenario.execute("scenario.damage",args).integer());assertEquals(2,m.entities.get("bob").resources().get("health"));
            args.put("amount",RuleValue.integer(100));assertEquals(2,m.scenario.execute("scenario.damage",args).integer());
            if(zero.equals("REMOVE"))assertFalse(m.entities.containsKey("bob"));else { assertEquals(0,m.entities.get("bob").resources().get("health"));assertEquals(!zero.equals("MARK"),m.entities.get("bob").attributes().get("alive").bool()); }
            assertEquals(4,state.entities().get("bob").resources().get("health"));
        }
        var source=example("grid-skirmish");((ObjectNode)source.at("/scenario/damage/wounds")).put("capAtRemaining",false).put("onZero","REMOVE");var runtime=runtime(source);var m=new RuntimeMachine(runtime,start(runtime),false);
        var args=new TreeMap<>(Map.of("target",RuleValue.entity("piece","bob-a"),"policy",RuleValue.text("wounds"),"amount",RuleValue.integer(4)));bad("RESOURCE_BOUNDS",()->m.scenario.execute("scenario.damage",args));args.put("amount",RuleValue.integer(-1));bad("DAMAGE_AMOUNT",()->m.scenario.execute("scenario.damage",args));args.put("policy",RuleValue.text("missing"));bad("DAMAGE_POLICY",()->m.scenario.execute("scenario.damage",args));
        args.put("policy",RuleValue.text("wounds"));args.put("amount",RuleValue.integer(3));assertEquals(3,m.scenario.execute("scenario.damage",args).integer());assertFalse(m.entities.containsKey("bob-a"));
    }
    @Test void rostersEnforceOwnershipCostCountUniquenessAndFactionAtomically() throws Exception {
        var source=example("grid-skirmish");var attributes=(ObjectNode)source.at("/entities/piece/attributes");attributes.set("unique",JSON.readTree("{\"type\":\"string\",\"initial\":\"\",\"visibility\":\"PUBLIC\"}"));attributes.set("faction",JSON.readTree("{\"type\":\"string\",\"initial\":\"blue\",\"visibility\":\"PUBLIC\"}"));var policy=(ObjectNode)source.at("/scenario/rosters/squad");policy.put("unique","unique").put("faction","faction");policy.putArray("factions").add("blue");
        var runtime=runtime(source);var m=new RuntimeMachine(runtime,start(runtime),false);var refs=RuleValue.list(RuleType.entity("piece"),List.of(RuleValue.entity("piece","alice-a"),RuleValue.entity("piece","alice-b")));var args=new TreeMap<>(Map.of("owner",RuleValue.player("alice"),"entities",refs,"policy",RuleValue.text("squad")));
        assertNull(m.scenario.execute("scenario.roster",args));attribute(m,"alice-a","unique",RuleValue.text("captain"));attribute(m,"alice-b","unique",RuleValue.text("captain"));bad("ROSTER_UNIQUE",()->m.scenario.execute("scenario.roster",args));attribute(m,"alice-b","unique",RuleValue.text("engineer"));m.scenario.execute("scenario.roster",args);
        attribute(m,"alice-a","faction",RuleValue.text("red"));bad("ROSTER_FACTION",()->m.scenario.execute("scenario.roster",args));attribute(m,"alice-a","faction",RuleValue.text("blue"));attribute(m,"alice-a","points",RuleValue.integer(-1));bad("ROSTER_COST",()->m.scenario.execute("scenario.roster",args));attribute(m,"alice-a","points",RuleValue.integer(6));bad("ROSTER_BUDGET",()->m.scenario.execute("scenario.roster",args));attribute(m,"alice-a","points",RuleValue.integer(5));
        args.put("owner",RuleValue.player("bob"));bad("ROSTER_MEMBER",()->m.scenario.execute("scenario.roster",args));args.put("owner",RuleValue.player("alice"));args.put("entities",RuleValue.list(RuleType.entity("piece"),List.of(RuleValue.entity("piece","alice-a"),RuleValue.entity("piece","alice-a"))));bad("ROSTER_MEMBER",()->m.scenario.execute("scenario.roster",args));args.put("entities",RuleValue.list(RuleType.entity("piece"),List.of()));bad("ROSTER_COUNT",()->m.scenario.execute("scenario.roster",args));args.put("policy",RuleValue.text("missing"));bad("ROSTER_POLICY",()->m.scenario.execute("scenario.roster",args));
        policy.put("budget",9);var invalid=runtime(source);var initial=invalid.initial("bad",List.of("alice","bob"));rejected("ROSTER_BUDGET",invalid.start(initial,0));assertTrue(initial.entities().isEmpty());rejected("SCENARIO_REQUIREMENT",invalid.start(invalid.initial("one",List.of("alice")),0));
    }
}
