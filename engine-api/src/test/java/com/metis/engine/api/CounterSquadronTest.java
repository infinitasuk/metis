package com.metis.engine.api;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import com.metis.engine.core.rules.runtime.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.metis.engine.api.RulesCatalogueTest.JSON;
import static org.junit.jupiter.api.Assertions.*;

class CounterSquadronTest {
    static ObjectNode pack() throws Exception {
        try(var in=CounterSquadronTest.class.getResourceAsStream("/rules/catalogue/armada-squadron-counter.pack.json")) { return (ObjectNode)JSON.readTree(in); }
    }
    static ObjectNode source() throws Exception {
        var source=CatalogueRostersTest.pricedSource();var units=(ArrayNode)source.get("units");
        var a=(ObjectNode)units.get(1).deepCopy();a.put("code","awing").put("displayName","A-Wing Squadron").put("maximumHullPoints",4).put("maximumMovementDistance",5).put("pointsCost",11);
        a.set("antiSquadronDice",JSON.readTree("[{\"color\":\"Blue\",\"count\":3}]"));((ObjectNode)a.get("pointsSource")).put("entryId","awing-source").put("rawPoints","11.0");units.add(a);
        var interceptor=(ObjectNode)units.get(0).deepCopy();interceptor.put("code","interceptor").put("displayName","TIE Interceptor Squadron").put("maximumMovementDistance",5).put("pointsCost",11);
        interceptor.set("antiSquadronDice",JSON.readTree("[{\"color\":\"Blue\",\"count\":4}]"));((ObjectNode)interceptor.get("pointsSource")).put("entryId","interceptor-source").put("rawPoints","11.0");units.add(interceptor);
        var keywords=List.of(List.of("Swarm"),List.of("Bomber","Escort"),List.of("Counter 2"),List.of("Counter 2","Swarm"));
        for(int i=0;i<units.size();i++) {
            var unit=(ObjectNode)units.get(i);unit.set("keywords",JSON.valueToTree(keywords.get(i)));unit.putNull("squadronAbility");
            unit.putObject("keywordSource").put("catalogue","fixture.cat").put("revision","1").put("profile",unit.get("displayName").textValue()).put("characteristic","Keywords");
            unit.set("abilitySource",unit.get("keywordSource").deepCopy());
        }
        return source;
    }
    static ObjectNode template() throws Exception {
        var template=(ObjectNode)JSON.readTree(RulesCatalogueService.compose(SelectableRostersTest.template(),pack()));
        RulesCatalogueService.populate(template,source(),pack());return template;
    }
    private static RulesRuntime runtime(ObjectNode source) {
        var compiled=new RulesCompiler().compile(source.toString());assertTrue(compiled.valid(),compiled.diagnostics().toString());return new RulesRuntime(compiled.definition());
    }
    private static RulesState accepted(RulesState.Result result) { assertTrue(result.accepted(),result.code()+": "+result.message());return result.state(); }
    private static void rejected(String code,RulesState.Result result) { assertFalse(result.accepted());assertEquals(code,result.code());assertNull(result.state()); }
    private static RulesState setup(RulesRuntime runtime,String policyA,List<String> a,String policyB,List<String> b,long... tape) {
        var input=JSON.valueToTree(List.of(Map.of("policy",policyA,"units",a),Map.of("policy",policyB,"units",b)));
        var initial=new CatalogueRosters(runtime.definition()).select(runtime.initial("counter",List.of("alice","bob"),RandomStream.tape(tape)),input);
        var state=accepted(runtime.start(initial,0));
        while(state.control().flow().phase().equals("deployment")) {
            String actor=state.players().get(state.control().flow().playerIndex());state=accepted(runtime.activate(state,actor,runtime.eligibleEntities(state,actor).getFirst(),state.version()));
            var offers=runtime.offeredActions(state,actor);assertEquals(1,offers.size());var offer=offers.getFirst();state=accepted(runtime.act(state,actor,offer.action(),offer.parameters(),state.version()));
        }
        return state;
    }
    private static RulesState pose(RulesState state,String id,int x,int y,Integer hull) {
        var units=new TreeMap<>(state.entities());var unit=units.get(id);var attrs=new TreeMap<>(unit.attributes());var health=new TreeMap<>(unit.resources());
        attrs.put("x",RuleValue.decimal(BigDecimal.valueOf(x)));attrs.put("y",RuleValue.decimal(BigDecimal.valueOf(y)));
        if(hull!=null){health.put("health",hull.longValue());attrs.put("alive",RuleValue.bool(hull>0));}
        units.put(id,new EvaluationContext.Entity(unit.type(),id,unit.owner(),attrs,health));
        return new RulesState(state.runtimeVersion(),state.definitionFingerprint(),state.sessionId(),state.version(),state.players(),state.globals(),units,state.control(),state.events());
    }
    private static Map<String,RuleValue> attack(String from,String to) { return Map.of("entity",RuleValue.entity("squadron",from),"target",RuleValue.entity("squadron",to)); }
    private static RulesState startAttack(RulesRuntime runtime,RulesState state) {
        state=accepted(runtime.activate(state,"alice","seat0-unit0",state.version()));
        return accepted(runtime.act(state,"alice","attack",attack("seat0-unit0","seat1-unit0"),state.version()));
    }
    private static RulesState respond(RulesRuntime runtime,RulesState state,String player,String choice) {
        var decision=state.control().decision();assertNotNull(decision);int option=decision.options().indexOf(RuleValue.text(choice));assertTrue(option>=0,decision.options().toString());
        return accepted(runtime.respond(state,player,decision.id(),option,state.version()));
    }
    private static long draws(RulesState s) { return s.control().mechanics().random().position(); }
    private static long health(RulesState s,String id) { return s.entities().get(id).resources().get("health"); }

    @Test void fourSourceProfilesAndKeywordsPopulateTheDefinitionAndMixedFleets() throws Exception {
        var template=template();
        assertEquals(JSON.readTree("[8,13,11,11]"),template.at("/state/points/initial"));
        assertEquals(JSON.readTree("[3,5,4,3]"),template.at("/state/hulls/initial"));
        assertEquals(500,template.at("/state/speeds/initial/2").intValue());assertEquals(500,template.at("/state/speeds/initial/3").intValue());
        assertEquals(JSON.readTree("[true,false,false,true]"),template.at("/state/swarmProfiles/initial"));
        assertEquals(JSON.readTree("[false,true,false,false]"),template.at("/state/escortProfiles/initial"));
        assertEquals(JSON.readTree("[[],[],[\"blue\",\"blue\"],[\"blue\",\"blue\"]]"),template.at("/state/counterPools/initial"));
        var roster=new CatalogueRosters(template.toString());assertEquals(19,roster.validate(0,"imperial",JSON.readTree("[\"123\",\"interceptor\"]")).points());
        assertEquals(24,roster.validate(1,"rebel",JSON.readTree("[\"456\",\"awing\"]")).points());
        assertEquals("ROSTER_BUDGET",roster.validate(0,"imperial",JSON.readTree("[\"123\",\"123\",\"interceptor\"]")).code());
    }
    @Test void unknownMissingOrUnimplementedKeywordDataRejectsImport() throws Exception {
        for(String invalid:List.of("[\"Counter 3\"]","[\"Rogue\"]","[\"Counter 2\",\"Counter 2\"]","[1]","null")) {
            var source=source();((ObjectNode)source.at("/units/2")).set("keywords",JSON.readTree(invalid));
            assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),source,pack()));
        }
        var missing=source();((ObjectNode)missing.at("/units/2")).remove("abilitySource");
        assertEquals("CATALOGUE_KEYWORDS_MISSING",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),missing,pack())).code());
        var named=source();((ObjectNode)named.at("/units/2")).put("squadronAbility","Unsupported named effect");
        assertEquals("CATALOGUE_SCOPE_UNSUPPORTED",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),named,pack())).code());
        var changed=source();((ObjectNode)changed.at("/units/2")).putArray("keywords").add("Swarm");var adapted=template();RulesCatalogueService.populate(adapted,changed,pack());
        assertTrue(adapted.at("/state/swarmProfiles/initial/2").booleanValue());assertTrue(adapted.at("/state/counterPools/initial/2").isEmpty(),"Traits come from source keywords, not a unit-name branch");
    }
    @Test void lethalDefenseStillOffersCounterAndDeclineFinishesWithoutMoreRandomness() throws Exception {
        var runtime=runtime(template());var state=setup(runtime,"imperial",List.of("123"),"rebel",List.of("awing"),0,0,0);
        state=pose(pose(state,"seat0-unit0",200,200,null),"seat1-unit0",300,200,1);var pending=startAttack(runtime,state);
        assertEquals(0,health(pending,"seat1-unit0"));assertEquals(RulesState.Status.RUNNING,pending.control().status());assertEquals(3,draws(pending));
        assertEquals("bob",pending.control().decision().player());String id=pending.control().decision().id();
        rejected("WRONG_RESPONDER",runtime.respond(pending,"alice",id,0,pending.version()));
        rejected("STALE_DECISION",runtime.respond(pending,"bob","old",0,pending.version()));
        rejected("CHOICE_INVALID",runtime.respond(pending,"bob",id,99,pending.version()));
        rejected("DECISION_PENDING",runtime.pass(pending,"alice",pending.version()));
        var ended=respond(runtime,runtime.restore(RulesStateCodec.encode(pending)),"bob","$pass");
        assertEquals(RulesState.Status.FINISHED,ended.control().status());assertEquals(List.of("alice"),ended.control().winners());assertEquals(3,draws(ended));
        rejected("NO_DECISION",runtime.respond(ended,"bob",id,0,ended.version()));
        assertEquals(1,ended.events().stream().filter(e->e.type().equals("game.attackResolved")).count());
    }
    @Test void destroyedCounterDefenderCanCauseMutualEliminationWithoutACounterChain() throws Exception {
        var runtime=runtime(template());var state=setup(runtime,"rebel",List.of("awing"),"imperial",List.of("interceptor"),0,0,0,0,0);
        state=pose(pose(state,"seat0-unit0",200,200,1),"seat1-unit0",300,200,1);
        var pending=startAttack(runtime,state);assertEquals(3,draws(pending));
        var ended=respond(runtime,runtime.restore(RulesStateCodec.encode(pending)),"bob","counter");
        assertEquals(5,draws(ended));assertEquals(0,health(ended,"seat0-unit0"));assertEquals(0,health(ended,"seat1-unit0"));
        assertEquals("mutual-elimination-draw",ended.control().outcome());assertTrue(ended.control().winners().isEmpty());assertNull(ended.control().decision());
        var attacks=ended.events().stream().filter(e->e.type().equals("game.attackResolved")).toList();assertEquals(2,attacks.size());
        assertFalse(attacks.getFirst().values().get("counter").bool());assertTrue(attacks.getLast().values().get("counter").bool());
    }
    @Test void survivingCounterDefenderCanWinTheBattle() throws Exception {
        var runtime=runtime(template());var state=setup(runtime,"rebel",List.of("awing"),"imperial",List.of("interceptor"),4,4,4,0,0);
        state=pose(pose(state,"seat0-unit0",200,200,1),"seat1-unit0",300,200,null);
        var ended=respond(runtime,startAttack(runtime,state),"bob","counter");assertEquals(List.of("bob"),ended.control().winners());assertEquals("elimination",ended.control().outcome());
    }
    @Test void swarmModifiesCounterAndResumesNestedChoicesExactlyOnceWithoutDefenderActivationCost() throws Exception {
        var runtime=runtime(template());var state=setup(runtime,"rebel",List.of("awing"),"imperial",List.of("interceptor","123"),4,4,4,4,4,0);
        state=pose(pose(pose(state,"seat0-unit0",200,200,null),"seat1-unit0",300,200,null),"seat1-unit1",300,300,null);
        var pending=startAttack(runtime,state);String oldCounter=pending.control().decision().id();
        var swarm=respond(runtime,runtime.restore(RulesStateCodec.encode(pending)),"bob","counter");
        assertEquals(5,draws(swarm));assertEquals("/windows/swarm",swarm.control().decision().path());assertEquals("bob",swarm.control().decision().player());
        assertEquals(2,swarm.control().mechanics().rolls().values().iterator().next().dice().size());
        rejected("STALE_DECISION",runtime.respond(swarm,"bob",oldCounter,0,swarm.version()));
        rejected("WRONG_RESPONDER",runtime.respond(swarm,"alice",swarm.control().decision().id(),0,swarm.version()));
        var chooseDie=respond(runtime,runtime.restore(RulesStateCodec.encode(swarm)),"bob","reroll");assertEquals(5,draws(chooseDie));
        var restored=runtime.restore(RulesStateCodec.encode(chooseDie));
        var result=runtime.respond(restored,"bob",restored.control().decision().id(),0,restored.version());var resolved=accepted(result);
        assertEquals(6,draws(resolved));assertEquals(3,health(resolved,"seat0-unit0"));assertEquals(3,health(resolved,"seat1-unit0"));
        assertEquals(1,resolved.control().flow().progress().get("alice").uses().get("act"));
        assertTrue(resolved.control().flow().progress().getOrDefault("bob",RulesState.Progress.empty()).uses().isEmpty());
        assertTrue(runtime.eligibleEntities(resolved,"bob").contains("seat1-unit0"));
        assertEquals(resolved,RulesRuntime.replay(restored,result.commit()));assertEquals(resolved,runtime.restore(RulesStateCodec.encode(resolved)));
    }
    @Test void escortProtectsAWingsFromNormalAttacksButNotFromCounter() throws Exception {
        var runtime=runtime(template());var state=setup(runtime,"rebel",List.of("awing","456"),"imperial",List.of("interceptor"),4,4,4,0,0);
        state=pose(pose(pose(state,"seat0-unit0",200,200,null),"seat0-unit1",200,300,null),"seat1-unit0",300,200,null);
        var resolved=respond(runtime,startAttack(runtime,state),"bob","counter");assertEquals(2,health(resolved,"seat0-unit0"));
        resolved=accepted(runtime.activate(resolved,"bob","seat1-unit0",resolved.version()));
        assertEquals("ACTION_INELIGIBLE",runtime.preview(resolved,"bob","attack",attack("seat1-unit0","seat0-unit0")).code());
        assertTrue(runtime.preview(resolved,"bob","attack",attack("seat1-unit0","seat0-unit1")).allowed());
        rejected("ACTION_INELIGIBLE",runtime.act(resolved,"bob","attack",attack("seat1-unit0","seat0-unit0"),resolved.version()));assertEquals(5,draws(resolved));
        var destroyedScreen=pose(resolved,"seat0-unit1",200,300,0);assertTrue(runtime.preview(destroyedScreen,"bob","attack",attack("seat1-unit0","seat0-unit0")).allowed());
        var distantScreen=pose(resolved,"seat0-unit1",800,500,null);assertTrue(runtime.preview(distantScreen,"bob","attack",attack("seat1-unit0","seat0-unit0")).allowed());
    }
}
