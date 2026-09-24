package com.metis.engine.api;

import com.metis.engine.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CpuOrdersTest {
    @Test void cpuPlansPrivatelyAndCompletesOrderBearingActivations() throws Exception {
        var mapper=new ObjectMapper();
        var structure=mapper.readValue("""
          {"phases":[
           {"code":"plan","label":"Plan","mode":"MANUAL","groupSize":1,"steps":[]},
           {"code":"act","label":"Act","mode":"ALTERNATING_UNITS","groupSize":1,"steps":[
            {"code":"shoot","label":"Shoot","actions":["attack_unit"],"minimum":0,"maximum":1},
            {"code":"move","label":"Move","actions":["move_unit"],"minimum":1,"maximum":1,"editableMovement":true}]}],
           "orders":{"planningPhase":"plan","activationPhase":"act","capacity":{"value":2},"tokenCapacity":{"value":1},"perTypeLimit":1,
            "orders":[{"code":"boost","label":"Boost","timing":"BEFORE_ATTACK","orderEffects":[{"kind":"ADD_ATTACK_DICE","amount":{"value":1}}]}]}}
          """,TurnStructure.class);
        var definition=new GameDefinition(1,"test","Test",1,"v1",2,2,0,2,List.of("a","b"),
                List.of(new UnitDefinition("a","a",1,500,1,20),new UnitDefinition("b","b",1,500,1,20)),structure);
        var s=new GameState(1,1,"setup",0,"setup",null,0,List.of(new Player(10,1,"cpu","a"),new Player(20,2,"human","b")),
                List.of(new UnitState(1,10,"a",100,200,0,"ready",20,0,0,1),new UnitState(2,20,"b",350,200,180,"ready",20,0,0,1)),
                new BoardState("test",1000,1000),List.of(),Map.of("distancePerSpeed",30));
        var engine=new AuthoritativeGameEngine(bound->5); var runner=new CpuBattleRunner(null,null,mapper);
        s=engine.applyAction(definition,s,new Action("start_game",0,Map.of())).resultingState();
        var view=new StateProjection(null,Set.of(10L),s.units()).state(s);
        var plan=new OrderEngine(definition).planningActions(view).stream().filter(a->a.actorPlayerId()==10).findFirst().orElseThrow();
        s=engine.applyAction(definition,s,CpuOrders.plan(definition,view,plan)).resultingState();
        assertEquals(List.of("boost","boost"),OrderEngine.queue(s,1).get("entries"));
        s=engine.applyAction(definition,s,new Action("plan_orders",20,Map.of("unitId",2,"orders",List.of("boost","boost")))).resultingState();
        s=engine.applyAction(definition,s,new Action("advance_phase",0,Map.of())).resultingState();
        for(int attempts=0;attempts<10 && s.activePlayerId()==10;attempts++) {
            view=new StateProjection(null,Set.of(10L),s.units()).state(s);
            var action=runner.chooseAction(definition,view); assertNotNull(action);
            var result=engine.applyAction(definition,s,action); assertTrue(result.accepted(),result.message()); s=result.resultingState();
        }
        assertEquals(20L,s.activePlayerId());
        assertEquals(1,s.events().stream().filter(e->e.type().equals("order_resolved")).count());
        assertEquals(1,s.events().stream().filter(e->e.type().equals("unit_attacked")).count());
        assertEquals(1,s.events().stream().filter(e->e.type().equals("unit_moved")).count());
        assertEquals(List.of("boost"),OrderEngine.queue(s,1).get("entries"));
    }
}
