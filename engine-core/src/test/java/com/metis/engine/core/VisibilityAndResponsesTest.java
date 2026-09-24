package com.metis.engine.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VisibilityAndResponsesTest {
    private GameState state(Map<String,Object> values) {
        return new GameState(1,1,"setup",0,"setup",null,0,List.of(new Player(10,1,"human","a"),new Player(20,2,"human","b")),
                List.of(new UnitState(1,10,"ALICE_UNIT",100,100,0,"ready",20,0,0,1),
                        new UnitState(2,20,"BOB_UNIT",150,100,180,"ready",20,0,1,1,Map.of(),Map.of("guard",1),Map.of())),
                new BoardState("custom",1000,1000),List.of(),values);
    }
    private GameDefinition definition(TurnStructure structure) {
        return new GameDefinition(1,"custom","Custom",1,"v1",2,2,0,2,List.of("a","b"),
                List.of(new UnitDefinition("ALICE_UNIT","a",3,300,1,20),new UnitDefinition("BOB_UNIT","b",3,300,1,20)),structure);
    }
    @Test
    void projectsNestedScopesHiddenUnitsAndUnknownInternalDataWithoutMutation() {
        var raw=state(Map.of("notes",Map.of("a",ScopedData.owner(10,"ALICE_SECRET"),"b",ScopedData.owner(20,"BOB_SECRET"),"deck",ScopedData.server("DECK_SECRET")),
                "serverOnly",Map.of("deck","DECK_SECRET"),"initialSetupOptions",Map.of("copiedSecret","COPY_SECRET"),"unknownInternal","UNKNOWN_SECRET"));
        var rules=new VisibilityRules(VisibilityRules.Audience.OWNER,List.of("notes"),List.of());
        var alice=new StateProjection(rules,Set.of(10L),raw.units()).state(raw);
        assertEquals(1,alice.units().size()); assertEquals(1,alice.units().getFirst().unitId());
        assertTrue(alice.values().toString().contains("ALICE_SECRET"));
        for(String secret:List.of("BOB_SECRET","DECK_SECRET","COPY_SECRET","UNKNOWN_SECRET")) assertFalse(alice.toString().contains(secret));
        var spectator=new StateProjection(rules,Set.of(),raw.units()).state(raw);
        assertTrue(spectator.units().isEmpty()); assertFalse(spectator.toString().contains("ALICE_SECRET"));
        assertEquals(2,raw.units().size()); assertTrue(raw.values().toString().contains("BOB_SECRET"));
        var projection=new StateProjection(rules,Set.of(10L),raw.units());
        assertEquals("hidden_event",projection.event(new GameEvent(1,"unit_moved",20L,Map.of("unitId",2,"positionX",700))).type());
        assertEquals("hidden_event",projection.event(new GameEvent(2,"deck_shuffled",null,Map.of("deck","DECK_SECRET"))).type());
        assertEquals("hidden_event",projection.event(new GameEvent(3,"choice",20L,ScopedData.owner(20,"BOB_SECRET"))).type());
        var hiddenMove=projection.event(new GameEvent(4,"unit_moved",20L,Map.of("unitId",2,"positionX",700,
                "phase","engage","round",2,"activePlayerId",20,"activation",Map.of("unitId",2),"unitChanges",List.of(raw.units().get(1)))));
        assertEquals("engage",hiddenMove.payload().get("phase"));
        assertFalse(hiddenMove.payload().containsKey("positionX"));
        assertFalse(hiddenMove.toString().contains("BOB_UNIT"));
    }

    @Test
    void privatePlanningRequiresBothPlayersAndOnlyRevealsDeclaredChoices() {
        var phase=new TurnStructure.Phase("planning","Planning",TurnStructure.Mode.MANUAL,List.of(),1,List.of(),false,
                List.of(new TurnStructure.PrivateInput("secret","Secret",List.of("left","right"),true,false),
                        new TurnStructure.PrivateInput("signal","Signal",List.of("hold","advance"),true,true)));
        var def=definition(new TurnStructure(false,List.of(phase,new TurnStructure.Phase("next","Next",TurnStructure.Mode.MANUAL,List.of(),1,List.of(),false))));
        var engine=new AuthoritativeGameEngine();
        var s=engine.applyAction(def,state(Map.of()),new Action("start_game",0,Map.of())).resultingState();
        for(long player:List.of(10L,20L)) {
            for(String input:List.of("secret","signal")) {
                var r=engine.applyAction(def,s,new Action("submit_private_choice",player,Map.of("inputCode",input,"value",input.equals("secret")?"left":"hold")));
                assertTrue(r.accepted()); s=r.resultingState();
            }
            if(player==10) assertEquals("PLANNING_INCOMPLETE",engine.applyAction(def,s,new Action("advance_phase",10,Map.of())).code());
        }
        var alice=new StateProjection(def.turnStructure().visibility(),Set.of(10L),s.units()).state(s);
        var stage=ResponseWindows.object(ResponseWindows.object(alice.values().get("privateChoices")).get("1:planning"));
        assertTrue(stage.containsKey("10")); assertFalse(stage.containsKey("20"));
        assertTrue(alice.events().stream().anyMatch(e->e.type().equals("hidden_event")));
        var ended=engine.applyAction(def,s,new Action("advance_phase",10,Map.of())).resultingState();
        assertEquals("next",ended.phaseCode());
        var publicState=new StateProjection(def.turnStructure().visibility(),Set.of(),ended.units()).state(ended);
        assertTrue(publicState.values().get("publicChoices").toString().contains("signal"));
        assertFalse(publicState.values().get("publicChoices").toString().contains("secret"));
        assertTrue(publicState.events().stream().filter(e->e.sequence()>1&&e.sequence()<6).allMatch(e->e.type().equals("hidden_event")));
    }

    private GameDefinition respondingDefinition() {
        return definition(new TurnStructure(false,List.of(new TurnStructure.Phase("engage","Engage",TurnStructure.Mode.ALTERNATING_UNITS,List.of(),1,
                List.of(new TurnStructure.Step("attack","Attack",List.of("attack_unit"),0,1,false,null),new TurnStructure.Step("move","Move",List.of("move_unit"),1,1,true,null)),false)),
                VisibilityRules.defaults(),List.of(new TurnStructure.ResponseRule("attack_unit","targetUnitId","Response",Map.of("declineDefense",true),List.of(),"readyDefenseTokens","defenseTokenType"))));
    }
    @Test
    void responsePausesBeforeRandomnessResumesOriginalActionOnceAndConsumesItsBudget() {
        var rolls=new AtomicInteger(); var engine=new AuthoritativeGameEngine(bound->{rolls.incrementAndGet();return 5;});
        var def=respondingDefinition(); var s=engine.applyAction(def,state(Map.of()),new Action("start_game",0,Map.of())).resultingState();
        var open=engine.applyAction(def,s,new Action("attack_unit",10,Map.of("unitId",1,"targetUnitId",2,"secret","REQUEST_SECRET")));
        assertTrue(open.accepted()); assertEquals("RESPONSE_REQUIRED",open.code()); assertEquals(0,rolls.get());
        s=open.resultingState(); String window=ResponseWindows.pending(s).get("windowId").toString();
        assertTrue(ActivationContext.from(s).uses().isEmpty());
        assertFalse(new StateProjection(VisibilityRules.defaults(),Set.of(10L),s.units()).state(s).toString().contains("REQUEST_SECRET"));
        assertEquals("RESPONSE_PENDING",engine.applyAction(def,s,new Action("end_activation",10,Map.of())).code());
        assertEquals("NOT_RESPONDER",engine.applyAction(def,s,new Action("respond",10,Map.of("windowId",window,"choice","decline"))).code());
        assertEquals("STALE_RESPONSE",engine.applyAction(def,s,new Action("respond",20,Map.of("windowId","old","choice","decline"))).code());
        assertEquals("RESPONSE_INVALID",engine.applyAction(def,s,new Action("respond",20,Map.of("windowId",window,"choice","forged"))).code());
        var resolved=engine.applyAction(def,s,new Action("respond",20,Map.of("windowId",window,"choice","resource:guard","targetUnitId",1)));
        assertTrue(resolved.accepted()); assertEquals(1,rolls.get());
        s=resolved.resultingState(); assertTrue(ResponseWindows.pending(s).isEmpty());
        assertEquals(10L,s.activePlayerId()); assertEquals(1,ActivationContext.from(s).uses().get("attack"));
        assertEquals(20,s.units().get(1).hullPoints()); assertEquals(1,s.units().get(1).exhaustedDefenseTokens().get("guard"));
        assertEquals("NO_RESPONSE_WINDOW",engine.applyAction(def,s,new Action("respond",20,Map.of("windowId",window,"choice","decline"))).code());
        assertEquals(1,rolls.get());
    }
    @Test
    void defenderCanDeclineAndInitiatorCannotPreselectTheResponse() {
        var engine=new AuthoritativeGameEngine(bound->5); var def=respondingDefinition();
        var s=engine.applyAction(def,state(Map.of()),new Action("start_game",0,Map.of())).resultingState();
        s=engine.applyAction(def,s,new Action("attack_unit",10,Map.of("unitId",1,"targetUnitId",2,"defenseTokenType","guard"))).resultingState();
        String window=ResponseWindows.pending(s).get("windowId").toString();
        s=engine.applyAction(def,s,new Action("respond",20,Map.of("windowId",window,"choice","decline"))).resultingState();
        assertEquals(19,s.units().get(1).hullPoints()); assertEquals(1,s.units().get(1).readyDefenseTokens().get("guard"));
    }
    @Test
    void restartingRemapsScopedOwnersWithoutChangingTheOriginalData() {
        var original=Map.of("nested",List.of(ScopedData.owner(20,"PRIVATE_NOTE"),ScopedData.server("SERVER_NOTE")));
        Object copied=ScopedData.remapOwners(original,Map.of(20L,200L));
        assertTrue(copied.toString().contains("ownerPlayerId=200"));
        assertTrue(original.toString().contains("ownerPlayerId=20"));
        assertFalse(original.toString().contains("ownerPlayerId=200"));
    }
}
