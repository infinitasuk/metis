package com.metis.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import com.metis.engine.core.rules.runtime.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.metis.engine.api.RulesCatalogueTest.*;
import static com.metis.engine.api.CatalogueRostersTest.pricedSource;
import static org.junit.jupiter.api.Assertions.*;

class SelectableRostersTest {
    static ObjectNode template() throws Exception {
        ObjectNode pack;
        try(var in=SelectableRostersTest.class.getResourceAsStream("/rules/catalogue/armada-squadron-rosters.pack.json")) { pack=(ObjectNode)JSON.readTree(in); }
        var template=(ObjectNode)JSON.readTree(RulesCatalogueService.compose(m8Resource(""),pack));
        RulesCatalogueService.populate(template,pricedSource(),pack);return template;
    }
    private static RulesState accepted(RulesState.Result result) { assertTrue(result.accepted(),result.code()+": "+result.message());return result.state(); }
    private static JsonNode selection(String first,String second) throws Exception {
        return JSON.readTree("[{\"policy\":\"imperial\",\"units\":"+first+"},{\"policy\":\"rebel\",\"units\":"+second+"}]");
    }
    @Test void capturesSelectedQuantitiesCostsAndDistinctInstancesWithoutChangingTheDefinition() throws Exception {
        var source=template();String original=source.toString();var roster=new CatalogueRosters(original);var runtime=new RulesRuntime(new RulesCompiler().compile(original).definition());
        assertTrue(roster.view().selectable());assertEquals(2,roster.view().seats().getFirst().choices().size());
        var initial=runtime.initial("selected",List.of("alice","bob"),RandomStream.seeded("12".repeat(32)));
        var chosen=roster.select(initial,selection("[\"123\",\"123\",\"123\"]","[\"456\",\"456\"]"));
        runtime.validateCheckpoint(chosen);
        assertEquals(initial.definitionFingerprint(),chosen.definitionFingerprint());assertEquals(initial.control(),chosen.control());
        assertEquals(original,source.toString());assertNotEquals(initial.globals().get("selectedRosters"),chosen.globals().get("selectedRosters"));
        var capture=roster.captured(runtime.restore(RulesStateCodec.encode(chosen)));
        assertEquals(List.of(24L,26L),capture.seats().stream().map(CatalogueRosters.SelectedSeat::points).toList());
        assertEquals(List.of("seat0-unit0","seat0-unit1","seat0-unit2"),capture.seats().getFirst().entityIds());
        var started=accepted(runtime.start(chosen,0));assertEquals(5,started.entities().size());
        assertEquals(3,started.entities().values().stream().filter(e->e.owner().equals("alice")&&e.attributes().get("points").integer()==8).count());
        assertEquals(2,started.entities().values().stream().filter(e->e.owner().equals("bob")&&e.attributes().get("points").integer()==13).count());
        assertEquals(capture,roster.captured(chosen));
        assertEquals("ROSTER_ALREADY_CAPTURED",assertThrows(RulesService.Failure.class,()->roster.select(started,selection("[\"123\"]","[\"456\"]"))).code());
    }
    @Test void acceptsFactionChangesAndRejectsBadSelectionsAtTheSharedBoundary() throws Exception {
        var source=template();var roster=new CatalogueRosters(source.toString());var runtime=new RulesRuntime(new RulesCompiler().compile(source.toString()).definition());
        var initial=runtime.initial("selection-errors",List.of("alice","bob"));
        var swapped=JSON.readTree("[{\"policy\":\"rebel\",\"units\":[\"456\",\"456\"]},{\"policy\":\"imperial\",\"units\":[\"123\"]}]");
        var changed=accepted(runtime.start(roster.select(initial,swapped),0));assertEquals("Rebel Alliance",changed.entities().get("seat0-unit0").attributes().get("faction").value());
        for(var item:List.of(new String[]{"[]","ROSTER_COUNT"},new String[]{"[\"456\"]","ROSTER_FACTION"},
                new String[]{"[\"123\",\"123\",\"123\",\"123\"]","ROSTER_COUNT"},new String[]{"[\"unknown\"]","ROSTER_UNIT_UNKNOWN"})) {
            var selected=selection(item[0],"[\"456\"]");
            assertEquals(item[1],assertThrows(RulesService.Failure.class,()->roster.select(initial,selected)).code());
        }
        assertEquals("ROSTER_BUDGET",assertThrows(RulesService.Failure.class,()->roster.select(initial,selection("[\"123\"]","[\"456\",\"456\",\"456\"]"))).code());
        assertEquals("ROSTER_REQUIRED",assertThrows(RulesService.Failure.class,()->roster.select(initial,null)).code());
        assertEquals("ROSTER_SEAT_COUNT",assertThrows(RulesService.Failure.class,()->roster.select(initial,JSON.readTree("[]"))).code());
        var forged=selection("[\"123\"]","[\"456\"]");((ObjectNode)forged.get(0)).put("points",0);
        assertEquals("ROSTER_INPUT_INVALID",assertThrows(RulesService.Failure.class,()->roster.select(initial,forged)).code());
        assertTrue(initial.entities().isEmpty());assertEquals(0,initial.version());
    }
    private static RulesState deployAll(RulesRuntime runtime,RulesState state) {
        while(state.control().flow().phase().equals("deployment")) {
            String actor=state.players().get(state.control().flow().playerIndex());
            state=accepted(runtime.activate(state,actor,runtime.eligibleEntities(state,actor).getFirst(),state.version()));
            var offers=runtime.offeredActions(state,actor);assertEquals(1,offers.size());var action=offers.getFirst();
            state=accepted(runtime.act(state,actor,action.action(),action.parameters(),state.version()));
        }
        return state;
    }
    @Test void everySelectedUnitHasASeparateLegalDeploymentSlot() throws Exception {
        var source=template();var runtime=new RulesRuntime(new RulesCompiler().compile(source.toString()).definition());
        var initial=new CatalogueRosters(source.toString()).select(runtime.initial("deployment",List.of("alice","bob")),selection("[\"123\",\"123\",\"123\"]","[\"456\",\"456\"]"));
        var state=deployAll(runtime,accepted(runtime.start(initial,0)));assertEquals("squadrons",state.control().flow().phase());
        assertTrue(state.entities().values().stream().allMatch(e->e.attributes().get("placed").bool()));
        assertEquals(5,state.entities().values().stream().map(e->List.of(e.attributes().get("x"),e.attributes().get("y"))).distinct().count());
        assertEquals(state,runtime.restore(RulesStateCodec.encode(state)));
    }
    private static RulesState nearTarget(RulesState state) {
        var entities=new TreeMap<>(state.entities());
        for(var id:List.of("seat0-unit0","seat0-unit1","seat1-unit0")) {
            var old=entities.get(id);if(old==null)continue;var attrs=new TreeMap<>(old.attributes());
            attrs.put("x",RuleValue.decimal(BigDecimal.valueOf(id.startsWith("seat0")?200:300)));
            attrs.put("y",RuleValue.decimal(BigDecimal.valueOf(id.equals("seat0-unit1")?300:200)));
            var resources=new TreeMap<>(old.resources());if(id.equals("seat1-unit0"))resources.put("health",1L);
            entities.put(id,new EvaluationContext.Entity(old.type(),old.id(),old.owner(),attrs,resources));
        }
        return new RulesState(state.runtimeVersion(),state.definitionFingerprint(),state.sessionId(),state.version(),state.players(),state.globals(),entities,state.control(),state.events());
    }
    @Test void swarmRerollsOnceAfterRestoreAndFirstCasualtyDoesNotEndAMultiUnitGame() throws Exception {
        var source=template();var runtime=new RulesRuntime(new RulesCompiler().compile(source.toString()).definition());
        var initial=new CatalogueRosters(source.toString()).select(runtime.initial("swarm",List.of("alice","bob"),RandomStream.tape(4,4,4,0)),selection("[\"123\",\"123\"]","[\"456\",\"456\"]"));
        var state=nearTarget(deployAll(runtime,accepted(runtime.start(initial,0))));
        state=accepted(runtime.activate(state,"alice","seat0-unit0",state.version()));
        state=accepted(runtime.act(state,"alice","attack",Map.of("entity",RuleValue.entity("squadron","seat0-unit0"),"target",RuleValue.entity("squadron","seat1-unit0")),state.version()));
        assertEquals(3,state.control().mechanics().random().position());assertEquals(1,state.entities().get("seat1-unit0").resources().get("health"));
        assertFalse(runtime.respond(state,"bob",state.control().decision().id(),0,state.version()).accepted());
        state=runtime.restore(RulesStateCodec.encode(state));
        int choice=state.control().decision().options().indexOf(RuleValue.text("reroll"));assertTrue(choice>=0);
        state=accepted(runtime.respond(state,"alice",state.control().decision().id(),choice,state.version()));
        state=accepted(runtime.respond(state,"alice",state.control().decision().id(),0,state.version()));
        assertEquals(4,state.control().mechanics().random().position());assertEquals(0,state.entities().get("seat1-unit0").resources().get("health"));
        assertEquals(RulesState.Status.RUNNING,state.control().status());assertNull(state.control().decision());
    }
    @Test void unsupportedSwarmDoesNotOpenAndEliminatingTheLastEnemyEndsTheGame() throws Exception {
        var source=template();var runtime=new RulesRuntime(new RulesCompiler().compile(source.toString()).definition());
        var initial=new CatalogueRosters(source.toString()).select(runtime.initial("last-enemy",List.of("alice","bob"),RandomStream.tape(0,0,0)),selection("[\"123\"]","[\"456\"]"));
        var state=nearTarget(deployAll(runtime,accepted(runtime.start(initial,0))));
        state=accepted(runtime.activate(state,"alice","seat0-unit0",state.version()));
        state=accepted(runtime.act(state,"alice","attack",Map.of("entity",RuleValue.entity("squadron","seat0-unit0"),"target",RuleValue.entity("squadron","seat1-unit0")),state.version()));
        assertEquals(RulesState.Status.FINISHED,state.control().status());assertNull(state.control().decision());assertEquals(List.of("alice"),state.control().winners());
        assertEquals(3,state.control().mechanics().random().position());
    }
}
