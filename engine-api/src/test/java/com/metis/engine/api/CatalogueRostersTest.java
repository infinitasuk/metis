package com.metis.engine.api;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import com.metis.engine.core.rules.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.api.RulesCatalogueTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogueRostersTest {
    static ObjectNode pack() throws Exception {
        try(var in=CatalogueRostersTest.class.getResourceAsStream("/rules/catalogue/armada-squadron-points.pack.json")) { return (ObjectNode)JSON.readTree(in); }
    }
    static ObjectNode pricedSource() throws Exception {
        var source=source();int index=0;
        for(var unit:source.get("units")) {
            var u=(ObjectNode)unit;int cost=index++==0?8:13;u.put("pointsCost",cost).put("uniqueKey","");
            u.putObject("pointsSource").put("repository","https://github.com/BSData/star-wars-armada").put("file","fixture.cat").put("revision","1")
                    .put("entryId",u.get("code").textValue()).put("url","https://example.test/pinned.cat").put("licence","CC BY-SA 4.0")
                    .put("commit","a".repeat(40)).put("sha256","b".repeat(64)).put("rawPoints",cost+".0");
        }
        return source;
    }
    static ObjectNode pricedTemplate() throws Exception {
        var template=(ObjectNode)JSON.readTree(RulesCatalogueService.compose(m8Resource(""),pack()));
        RulesCatalogueService.populate(template,pricedSource(),pack());return template;
    }
    @Test void importsExactPointsAndProvenanceAndUsesSeatSpecificSharedPolicies() throws Exception {
        var source=pricedSource();((ObjectNode)source.at("/units/0")).put("pointsCost",11);((ObjectNode)source.at("/units/0/pointsSource")).put("rawPoints","11.0");
        var template=(ObjectNode)JSON.readTree(RulesCatalogueService.compose(m8Resource(""),pack()));RulesCatalogueService.populate(template,source,pack());
        var rosters=new CatalogueRosters(template.toString());rosters.validateDefaults();
        assertEquals(11,rosters.view().units().getFirst().points());
        assertEquals(source.at("/units/0/pointsSource"),JSON.readTree(rosters.view().units().getFirst().provenance()));
        assertTrue(rosters.validate(0,JSON.readTree("[\"123\"]")).valid());
        assertEquals("ROSTER_FACTION",rosters.validate(0,JSON.readTree("[\"456\"]")).code());
        assertEquals("ROSTER_COUNT",rosters.validate(0,JSON.readTree("[\"123\",\"123\"]")).code());
        assertEquals("ROSTER_UNIT_UNKNOWN",rosters.validate(0,JSON.readTree("[\"unknown\"]")).code());
        assertThrows(RulesService.Failure.class,()->rosters.validate(2,JSON.readTree("[]")));
        assertThrows(RulesService.Failure.class,()->rosters.validate(0,JSON.readTree("[{\"code\":\"123\",\"points\":0}]")));
        var unavailable=new CatalogueRosters(resource("").toString());assertFalse(unavailable.view().available());
        assertEquals("CATALOGUE_ROSTER_UNAVAILABLE",assertThrows(RulesService.Failure.class,()->unavailable.validate(0,JSON.readTree("[]"))).code());
    }
    @Test void missingEstimatedFractionalOrUnverifiableCostsFailImport() throws Exception {
        for(String value:List.of("null","0","-1","8.5","\"8\"","9223372036854775808")) {
            var source=pricedSource();((ObjectNode)source.at("/units/0")).set("pointsCost",JSON.readTree(value));
            assertEquals("CATALOGUE_POINTS_MISSING",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(pricedTemplate(),source,pack())).code());
        }
        for(String field:List.of("commit","sha256","file","revision","entryId","url","repository","licence","rawPoints")) {
            var source=pricedSource();((ObjectNode)source.at("/units/0/pointsSource")).remove(field);
            assertEquals("CATALOGUE_POINTS_PROVENANCE",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(pricedTemplate(),source,pack())).code());
        }
        for(String raw:List.of("7.0","unknown")) {
            var source=pricedSource();((ObjectNode)source.at("/units/0/pointsSource")).put("rawPoints",raw);
            assertEquals("CATALOGUE_POINTS_PROVENANCE",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(pricedTemplate(),source,pack())).code());
        }
    }
    @Test void preflightAndRuntimeAgreeAndRejectAtomicallyAtTheBudgetBoundary() throws Exception {
        for(int budget:List.of(12,13)) {
            var template=pricedTemplate();((ObjectNode)template.at("/scenario/rosters/rebel")).put("budget",budget);
            var rosters=new CatalogueRosters(template.toString());var report=rosters.validate(1,JSON.readTree("[\"456\"]"));
            var runtime=new RulesRuntime(new RulesCompiler().compile(template.toString()).definition());
            var initial=runtime.initial("points",List.of("alice","bob"),RandomStream.seeded("12".repeat(32)));
            var result=runtime.start(initial,0);assertEquals(report.valid(),result.accepted());
            assertEquals(13,report.points());
            if(budget==12) { assertEquals("ROSTER_BUDGET",result.code());assertTrue(initial.entities().isEmpty());assertEquals(0,initial.version()); }
            else {
                assertEquals(8,result.state().entities().get("alice").attributes().get("points").integer());
                assertEquals(13,result.state().entities().get("bob").attributes().get("points").integer());
                assertEquals(result.state(),runtime.restore(RulesStateCodec.encode(result.state())));
            }
        }
    }
    @Test void deploymentOffersOnlyTheActingSeatsZoneAndPosition() throws Exception {
        var runtime=new RulesRuntime(new RulesCompiler().compile(pricedTemplate().toString()).definition());
        var result=runtime.start(runtime.initial("deployment",List.of("alice","bob")),0);assertTrue(result.accepted());var state=result.state();
        for(int seat=0;seat<2;seat++) {
            String actor=state.players().get(seat);result=runtime.activate(state,actor,actor,state.version());assertTrue(result.accepted());state=result.state();
            var offered=runtime.offeredActions(state,actor);assertEquals(1,offered.size());
            assertEquals("deployment"+seat,offered.getFirst().parameters().get("zone").value());
            assertEquals(seat==0?200:800,offered.getFirst().parameters().get("x").number().intValueExact());
            result=runtime.act(state,actor,offered.getFirst().action(),offered.getFirst().parameters(),state.version());assertTrue(result.accepted(),result.code());state=result.state();
        }
        assertEquals("squadrons",state.control().flow().phase());
    }
}
