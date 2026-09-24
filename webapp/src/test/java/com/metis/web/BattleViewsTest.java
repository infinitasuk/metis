package com.metis.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.api.*;
import com.metis.engine.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BattleViewsTest {
    @Test
    void everyRuntimeDtoUsesPlayerProjectionAndAdminIsNotOmniscient() throws Exception {
        var mapper=new ObjectMapper(); var battle=mock(BattleRepository.class); var definitions=mock(DefinitionRepository.class);
        var rules=new VisibilityRules(VisibilityRules.Audience.OWNER,List.of("notes"),List.of());
        var def=new GameDefinition(1,"custom","Custom",1,"v1",2,2,0,2,List.of("a","b"),List.of(),
                new TurnStructure(false,List.of(new TurnStructure.Phase("plan","Plan",TurnStructure.Mode.MANUAL,List.of(),1,List.of(),false)),rules,List.of()));
        var state=new GameState(42,1,"running",1,"plan",null,3,
                List.of(new Player(10,1,"human","a"),new Player(20,2,"human","b")),
                List.of(new UnitState(1,10,"A",10,10,0,"ready"),new UnitState(2,20,"BOB_UNIT",777,777,0,"ready")),new BoardState("test",1000,1000),
                List.of(new GameEvent(1,"private_choice_submitted",20L,ScopedData.owner(20,Map.of("choice","BOB_SECRET")))),
                Map.of("notes",Map.of("a",ScopedData.owner(10,"ALICE_SECRET"),"b",ScopedData.owner(20,"BOB_SECRET")),"serverOnly",Map.of("deck","DECK_SECRET")));
        var node=mapper.createObjectNode(); node.put("sessionId",42); node.put("gameVersionId",1); node.put("status","running"); node.put("state",mapper.writeValueAsString(state));
        var session=mapper.treeToValue(node,BattleRepository.BattleSessionDetail.class);
        when(battle.findSession(42)).thenReturn(Optional.of(session));
        when(battle.controlledPlayers(42,1)).thenReturn(Set.of(10L));
        when(battle.controlledPlayers(42,2)).thenReturn(Set.of(20L));
        when(battle.controlledPlayers(42,5)).thenReturn(Set.of());
        when(definitions.findSessionDefinition(42,1)).thenReturn(Optional.of(def));
        var views=new BattleViews(battle,definitions,mapper);
        var alice=new MetisUser(1,"alice","Alice","user"); var bob=new MetisUser(2,"bob","Bob","user"); var admin=new MetisUser(5,"admin","Admin","admin");
        for(Object body:List.of(state,session,new ActionResult(true,"OK","Accepted",state))) {
            String a=mapper.writeValueAsString(views.project(body,42L,alice));
            assertTrue(a.contains("ALICE_SECRET")); assertFalse(a.contains("BOB_SECRET")); assertFalse(a.contains("BOB_UNIT")); assertFalse(a.contains("DECK_SECRET"));
            String b=mapper.writeValueAsString(views.project(body,42L,bob));
            assertTrue(b.contains("BOB_SECRET")); assertFalse(b.contains("ALICE_SECRET")); assertFalse(b.contains("DECK_SECRET"));
            String spectator=mapper.writeValueAsString(views.project(body,42L,admin));
            assertFalse(spectator.contains("ALICE_SECRET")); assertFalse(spectator.contains("BOB_SECRET")); assertFalse(spectator.contains("DECK_SECRET"));
        }
        var event=new BattleRepository.SessionEvent(1,1,"private_choice_submitted",20L,mapper.writeValueAsString(state.events().getFirst().payload()),"now");
        assertEquals("hidden_event",((BattleRepository.SessionEvent)views.project(event,42L,alice)).type());
        assertTrue(((BattleRepository.SessionEvent)views.project(event,42L,bob)).payload().contains("BOB_SECRET"));
        var log=new BattleRepository.EngineLog(1,42,"INFO","HTTP","BOB_SECRET","{\"parameters\":\"DECK_SECRET\"}","now");
        assertFalse(mapper.writeValueAsString(views.project(log,42L,admin)).contains("SECRET"));
        var unit=new BattleRepository.SessionUnit(2,42,20,100,1,"BOB_ROSTER",null,null,null,"ready","BOB_SECRET");
        assertNull(views.project(unit,42L,alice));
        assertEquals("{}",((BattleRepository.SessionUnit)views.project(unit,42L,bob)).state());
        assertEquals(Map.of("compatible",true),views.project(Map.of("compatible",true),42L,alice));
        assertTrue(session.state().contains("DECK_SECRET"),"Projection must not mutate authoritative data");
    }
}
