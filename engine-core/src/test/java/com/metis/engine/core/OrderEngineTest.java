package com.metis.engine.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.metis.engine.core.OrderRules.*;
import static com.metis.engine.core.TurnStructure.Mode.*;

class OrderEngineTest {
    private final AuthoritativeGameEngine engine = new AuthoritativeGameEngine(bound -> 5);
    private Amount fixed(int n) { return new Amount(null,n,1,false); }
    private Effect effect(Kind kind,int n) { return new Effect(kind,fixed(n),Map.of()); }
    private UnitDefinition profile(String code,String classification,int command) {
        return new UnitDefinition(code,"side",2,400,1,20,1,4,0,code,classification,
                Map.of(0,new ManeuverProfile(0,List.of(0)),1,new ManeuverProfile(1,List.of(1)),2,new ManeuverProfile(2,List.of(1,1))),10,
                Map.of("front",List.of(new AttackDie("generic",1))),List.of(),Map.of("front",2,"rear",2),List.of(),Map.of(),command,4,2);
    }
    private GameDefinition definition() {
        var orders = new OrderRules("plan","engage",List.of("Carrier"),new Amount("commandValue",0,1,false),fixed(1),1,List.of(
                new Order("thrust","Thrust",Timing.BEFORE_MOVE,List.of(effect(Kind.CHANGE_SPEED,1),new Effect(Kind.EXTRA_YAW,fixed(1),Map.of("maximumClicks",3,"minimumSpeed",1))),List.of(effect(Kind.CHANGE_SPEED,1))),
                new Order("restore","Restore",Timing.ON_REVEAL,List.of(new Effect(Kind.RESTORE,fixed(4),Map.of("costs",Map.of("health",3,"shield",2,"transfer",1)))),List.of(new Effect(Kind.RESTORE,fixed(2),Map.of("costs",Map.of("health",3,"shield",2,"transfer",1))))),
                new Order("fire","Fire",Timing.BEFORE_ATTACK,List.of(effect(Kind.ADD_ATTACK_DICE,1)),List.of(effect(Kind.REROLL_ATTACK_DICE,1))),
                new Order("coordinate","Coordinate",Timing.ON_REVEAL,List.of(new Effect(Kind.GRANT_ACTIVATIONS,fixed(1),Map.of("range",150,"classifications",List.of("Drone"),"actions",List.of("move_unit","attack_unit")))),List.of())));
        var phases=List.of(new TurnStructure.Phase("plan","Plan",MANUAL,List.of(),1,List.of(),false),
                new TurnStructure.Phase("engage","Engage",ALTERNATING_UNITS,List.of("Carrier"),1,List.of(
                        new TurnStructure.Step("shoot","Shoot",List.of("attack_unit"),0,2,false,null),new TurnStructure.Step("move","Move",List.of("move_unit"),0,1,true,null)),false),
                new TurnStructure.Phase("support","Support",ALTERNATING_UNITS,List.of("Drone"),1,List.of(new TurnStructure.Step("act","Act",List.of("move_unit","attack_unit"),0,1,false,null)),false),
                new TurnStructure.Phase("cleanup","Cleanup",MANUAL,List.of(),1,List.of(),true));
        return new GameDefinition(1,"custom","Custom",1,"v1",2,2,0,2,List.of("side"),List.of(profile("carrier","Carrier",2),profile("drone","Drone",0)),new TurnStructure(false,phases,null,List.of(),orders));
    }
    private GameState state() {
        return new GameState(1,1,"setup",0,"setup",null,0,List.of(new Player(10,1,"human","side"),new Player(20,2,"human","side")),List.of(
                new UnitState(1,10,"carrier",100,200,0,"ready",18,2,0,1,Map.of("front",0,"rear",2),Map.of(),Map.of()),
                new UnitState(2,20,"carrier",350,200,180,"ready",20,4,0,1,Map.of("front",2,"rear",2),Map.of(),Map.of()),
                new UnitState(3,10,"drone",100,300,0,"ready",20,0,0,1)),new BoardState("custom",1000,1000),List.of(),Map.of("distancePerSpeed",40));
    }
    private GameState act(GameState s,String type,long actor,Map<String,Object> p) {
        var result=engine.applyAction(definition(),s,new Action(type,actor,p)); assertTrue(result.accepted(),result.code()+": "+result.message());
        assertEquals(s.stateVersion()+1,result.resultingState().stateVersion()); assertEquals(s.events().size()+1,result.resultingState().events().size()); return result.resultingState();
    }
    private GameState planned(String first) {
        var s=act(state(),"start_game",0,Map.of());
        s=act(s,"plan_orders",10,Map.of("unitId",1,"orders",List.of(first,"fire")));
        return act(s,"plan_orders",20,Map.of("unitId",2,"orders",List.of("restore","thrust")));
    }
    private GameState active(String first) { return act(act(planned(first),"advance_phase",0,Map.of()),"activate_unit",10,Map.of("unitId",1)); }
    private Map<String,Object> resolve(String code,String source) { return Map.of("unitId",1,"orderCode",code,"source",source); }

    @Test void queuesArePrivateCommittedAndReplenishedAtTail() {
        var s=act(state(),"start_game",0,Map.of());
        assertEquals("ORDER_PLANNING_INCOMPLETE",engine.applyAction(definition(),s,new Action("advance_phase",0,Map.of())).code());
        assertFalse(engine.applyAction(definition(),s,new Action("plan_orders",20,Map.of("unitId",1,"orders",List.of("fire","fire")))).accepted());
        s=planned("thrust");
        for (var owned:List.of(Set.of(10L),Set.of(20L),Set.<Long>of())) {
            var view=new StateProjection(null,owned,s.units()).state(s);
            var queues=(Map<?,?>)view.values().get("orderQueues"); assertEquals(owned.size(),queues.size());
            assertEquals(owned.size(),view.events().stream().filter(e->e.type().equals("orders_planned")).count());
        }
        s=act(s,"advance_phase",0,Map.of());
        assertEquals("ORDER_REVEAL_REQUIRED",engine.applyAction(definition(),s,new Action("move_unit",10,Map.of("unitId",1,"speed",1,"yaw",List.of(0)))).code());
        s=act(s,"activate_unit",10,Map.of("unitId",1)); assertEquals("thrust",OrderEngine.state(s,1).get("revealed")); assertEquals(List.of("fire"),OrderEngine.queue(s,1).get("entries"));
        s=act(s,"end_activation",10,Map.of()); s=act(s,"activate_unit",20,Map.of("unitId",2)); s=act(s,"end_activation",20,Map.of());
        s=act(s,"activate_unit",10,Map.of("unitId",3)); s=act(s,"end_activation",10,Map.of()); s=act(s,"advance_phase",0,Map.of());
        assertEquals(2,s.round());
        assertFalse(engine.applyAction(definition(),s,new Action("plan_orders",10,Map.of("unitId",1,"orders",List.of("restore","thrust")))).accepted());
        s=act(s,"plan_orders",10,Map.of("unitId",1,"orders",List.of("restore")));
        assertEquals(List.of("fire","restore"),OrderEngine.queue(s,1).get("entries"));
    }
    @Test void combinedSourcesRespectCapacityTimingAndOneResolution() {
        var s=active("thrust"); s=act(s,"store_order",10,Map.of("unitId",1));
        assertFalse(engine.applyAction(definition(),s,new Action("store_order",10,Map.of("unitId",1))).accepted());
        var p=new HashMap<>(resolve("thrust","token"));p.put("speedDelta",-1);
        s=act(s,"resolve_order",10,p); assertEquals(0,s.units().getFirst().currentSpeed());
        assertEquals("ORDER_ALREADY_RESOLVED",engine.applyAction(definition(),s,new Action("resolve_order",10,p)).code());
        assertFalse(engine.applyAction(definition(),s,new Action("move_unit",10,Map.of("unitId",1,"speed",1,"yaw",List.of(0)))).accepted());
        s=act(s,"move_unit",10,Map.of("unitId",1,"speed",0,"yaw",List.of(0))); assertEquals(100,s.units().getFirst().positionX());
        assertFalse(engine.applyAction(definition(),s,new Action("attack_unit",10,Map.of("unitId",1,"targetUnitId",2))).accepted());
    }
    @Test void restorationRejectsOverspendWithoutConsumingOrder() {
        var s=active("restore"); var p=new HashMap<>(resolve("restore","order"));
        p.put("operations",List.of(Map.of("resource","health","amount",2)));
        assertFalse(engine.applyAction(definition(),s,new Action("resolve_order",10,p)).accepted()); assertEquals(true,OrderEngine.state(s,1).get("available"));
        p.put("operations",List.of(Map.of("resource","health","amount",1),Map.of("resource","transfer","amount",1,"from","rear","to","front")));
        s=act(s,"resolve_order",10,p); assertEquals(19,s.units().getFirst().hullPoints()); assertEquals(Map.of("front",1,"rear",1),s.units().getFirst().shieldZones());
    }
    @Test void bonusCanApplyToSecondAttackAndIsConsumedExactlyOnce() {
        var s=active("fire"); s=act(s,"attack_unit",10,Map.of("unitId",1,"targetUnitId",2));
        s=act(s,"resolve_order",10,resolve("fire","order"));
        s=act(s,"attack_unit",10,Map.of("unitId",1,"targetUnitId",2));
        assertEquals(1,s.events().getLast().payload().get("orderBonusDice")); assertEquals(0,OrderEngine.modifier(s,1,"attackDice"));
        assertFalse(engine.applyAction(definition(),s,new Action("resolve_order",10,resolve("fire","order"))).accepted());
    }
    @Test void supportActivationsUseTheirOwnBudgetsAndAreSkippedLater() {
        var s=act(active("coordinate"),"resolve_order",10,resolve("coordinate","order"));
        assertFalse(engine.applyAction(definition(),s,new Action("end_activation",10,Map.of())).accepted());
        s=act(s,"activate_support_unit",10,Map.of("unitId",3));
        s=act(s,"attack_unit",10,Map.of("unitId",3,"targetUnitId",2));
        assertFalse(engine.applyAction(definition(),s,new Action("attack_unit",10,Map.of("unitId",3,"targetUnitId",2))).accepted());
        s=act(s,"move_unit",10,Map.of("unitId",3,"positionX",110,"positionY",300,"facing",0));
        s=act(s,"reset_movement",10,Map.of("unitId",3)); assertEquals(100,s.units().get(2).positionX());
        s=act(s,"end_support_activation",10,Map.of()); assertTrue(OrderEngine.spentActivation(s,3));
        s=act(s,"end_order_grant",10,Map.of()); assertTrue(ActivationContext.from(s).uses().isEmpty());
        s=act(s,"end_activation",10,Map.of());s=act(s,"activate_unit",20,Map.of("unitId",2));s=act(s,"end_activation",20,Map.of());
        assertEquals("cleanup",s.phaseCode());s=act(s,"advance_phase",0,Map.of());assertFalse(OrderEngine.spentActivation(s,3));
    }

    private GameState withTokens(GameState s, String code) {
        var values=new HashMap<>(s.values()); var records=ResponseWindows.object(values.get("orderState")); var record=OrderEngine.state(s,1);
        record.put("tokens",Map.of(code,1)); records.put("1",record); values.put("orderState",records);
        return new GameState(s.sessionId(),s.definitionVersionId(),s.status(),s.round(),s.phaseCode(),s.activePlayerId(),s.stateVersion(),s.players(),s.units(),s.board(),s.events(),values);
    }
    @Test void fullBankRequiresExplicitReplacementAndDuplicateTypesAreRejected() {
        var s=withTokens(active("fire"),"thrust");
        assertEquals("TOKEN_CAPACITY",engine.applyAction(definition(),s,new Action("store_order",10,Map.of("unitId",1))).code());
        assertEquals(Map.of("thrust",1),OrderEngine.state(s,1).get("tokens"));
        s=act(s,"store_order",10,Map.of("unitId",1,"replaceToken","thrust")); assertEquals(Map.of("fire",1),OrderEngine.state(s,1).get("tokens"));
        s=withTokens(active("thrust"),"thrust");
        assertEquals("TOKEN_TYPE_LIMIT",engine.applyAction(definition(),s,new Action("store_order",10,Map.of("unitId",1,"replaceToken","thrust"))).code());
    }
    @Test void bothSourcesResolveTogetherAndConversionClosesAfterActing() {
        var s=withTokens(active("fire"),"fire"); s=act(s,"resolve_order",10,resolve("fire","both"));
        assertEquals(Map.of(),OrderEngine.state(s,1).get("tokens")); assertEquals(1,OrderEngine.modifier(s,1,"attackDice")); assertEquals(1,OrderEngine.modifier(s,1,"rerolls"));
        s=act(s,"attack_unit",10,Map.of("unitId",1,"targetUnitId",2)); assertEquals(0,OrderEngine.modifier(s,1,"rerolls"));
        s=act(active("restore"),"attack_unit",10,Map.of("unitId",1,"targetUnitId",2));
        assertEquals("CONVERSION_WINDOW_CLOSED",engine.applyAction(definition(),s,new Action("store_order",10,Map.of("unitId",1))).code());
        assertEquals("ORDER_TIMING",engine.applyAction(definition(),s,new Action("resolve_order",10,resolve("restore","order"))).code());
    }
    @Test void yawAllowanceSurvivesEditingAndResetButCannotBeResolvedAgain() {
        var s=act(active("thrust"),"resolve_order",10,resolve("thrust","order"));
        assertFalse(engine.applyAction(definition(),s,new Action("move_unit",10,Map.of("unitId",1,"speed",1,"yaw",List.of(3)))).accepted());
        s=act(s,"move_unit",10,Map.of("unitId",1,"speed",1,"yaw",List.of(2))); assertEquals(45,s.units().getFirst().facing());
        s=act(s,"reset_movement",10,Map.of("unitId",1));assertEquals(100,s.units().getFirst().positionX());assertEquals(0,s.units().getFirst().facing());
        assertEquals(1,OrderEngine.modifier(s,1,"yawBonus"));
        assertFalse(engine.applyAction(definition(),s,new Action("resolve_order",10,resolve("thrust","order"))).accepted());
        s=act(s,"move_unit",10,Map.of("unitId",1,"speed",1,"yaw",List.of(-2))); assertEquals(315,s.units().getFirst().facing());
    }
    @Test void supportAttackResponseResumesOnceWithoutSpendingParentBudget() {
        var s=act(active("coordinate"),"resolve_order",10,resolve("coordinate","order"));s=act(s,"activate_support_unit",10,Map.of("unitId",3));
        var base=definition(); var turns=base.turnStructure();
        var def=new GameDefinition(1,"custom","Custom",1,"v1",2,2,0,2,base.factionCodes(),base.unitDefinitions(),new TurnStructure(false,turns.phases(),turns.visibility(),
                List.of(new TurnStructure.ResponseRule("attack_unit","targetUnitId","Respond",Map.of("declineDefense",true),List.of(),null,null)),turns.orders()));
        var rolls=new java.util.concurrent.atomic.AtomicInteger(); var respondingEngine=new AuthoritativeGameEngine(bound->{rolls.incrementAndGet();return 5;});
        var opened=respondingEngine.applyAction(def,s,new Action("attack_unit",10,Map.of("unitId",3,"targetUnitId",2)));
        assertEquals("RESPONSE_REQUIRED",opened.code()); assertEquals(1,rolls.get());
        assertEquals("awaiting_defense",ResponseWindows.object(opened.resultingState().values().get("attackRoll")).get("status"));
        assertEquals(List.of(),OrderEngine.grant(opened.resultingState()).get("used"));
        var response=new Action("respond",20,Map.of("windowId",ResponseWindows.pending(opened.resultingState()).get("windowId"),"choice","decline"));
        var resumed=respondingEngine.applyAction(def,opened.resultingState(),response);assertTrue(resumed.accepted());assertEquals(1,rolls.get());
        assertEquals(List.of("attack_unit"),OrderEngine.grant(resumed.resultingState()).get("used"));assertTrue(ActivationContext.from(resumed.resultingState()).uses().isEmpty());
        assertEquals(s.events().size()+2,resumed.resultingState().events().size());
        assertEquals("NO_RESPONSE_WINDOW",respondingEngine.applyAction(def,resumed.resultingState(),response).code());assertEquals(1,rolls.get());
    }
}
