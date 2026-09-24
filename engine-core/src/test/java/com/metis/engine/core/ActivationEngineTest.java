package com.metis.engine.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.metis.engine.core.TurnStructure.Mode.*;

class ActivationEngineTest {
    private final AuthoritativeGameEngine engine = new AuthoritativeGameEngine(bound -> 5);
    private TurnStructure.Step move() { return new TurnStructure.Step("move", "Move", List.of("move_unit"), 1, 1, true, null); }
    private TurnStructure.Phase phase(String code, TurnStructure.Mode mode, int group, List<TurnStructure.Step> steps) {
        return new TurnStructure.Phase(code, code, mode, List.of(), group, steps, false);
    }
    private GameDefinition definition(TurnStructure.Phase... phases) {
        return new GameDefinition(1,"custom","Custom",1,"v1",2,2,0,2,List.of("a","b"),
                List.of(new UnitDefinition("a","a",3,300,1,20),new UnitDefinition("b","b",3,300,1,20)),
                new TurnStructure(false,List.of(phases)));
    }
    private GameState state() {
        return new GameState(1,1,"setup",0,"setup",null,0,List.of(new Player(10,1,"human","a"),new Player(20,2,"human","b")),
                List.of(new UnitState(1,10,"a",100,100,0,"ready",20,0,0,1),new UnitState(2,10,"a",100,500,0,"ready",20,0,0,1),
                        new UnitState(3,20,"b",250,100,180,"ready",20,0,0,1)),
                new BoardState("board",1000,1000),List.of(),Map.of("distancePerSpeed",100));
    }
    private GameState act(GameDefinition def,GameState state,String type,long actor,Map<String,Object> params) {
        var result=engine.applyAction(def,state,new Action(type,actor,params));
        assertTrue(result.accepted(),result.code()+": "+result.message());
        assertEquals(state.stateVersion()+1,result.resultingState().stateVersion());
        return result.resultingState();
    }
    @Test
    void alternatesUnitsSkipsExhaustedPlayerAndDoesNotEndPhaseEarly() {
        var def=definition(phase("engage",ALTERNATING_UNITS,1,List.of(move())),phase("cleanup",MANUAL,1,List.of()));
        var s=act(def,state(),"start_game",0,Map.of());
        assertNull(s.events().getLast().actorPlayerId());
        assertFalse(engine.applyAction(def,s,new Action("end_turn",10,Map.of())).accepted());
        s=act(def,s,"move_unit",10,Map.of("unitId",1,"positionX",150,"positionY",100,"facing",30));
        assertEquals(10L,s.activePlayerId());
        assertFalse(engine.applyAction(def,s,new Action("move_unit",10,Map.of("unitId",2,"positionX",150,"positionY",500,"facing",0))).accepted());
        s=act(def,s,"end_activation",10,Map.of()); assertEquals(20L,s.activePlayerId());
        s=act(def,s,"move_unit",20,Map.of("unitId",3,"positionX",300,"positionY",100,"facing",180));
        s=act(def,s,"end_activation",20,Map.of()); assertEquals(10L,s.activePlayerId()); assertEquals("engage",s.phaseCode());
        assertFalse(engine.applyAction(def,s,new Action("move_unit",10,Map.of("unitId",1,"positionX",125,"positionY",100,"facing",0))).accepted());
        s=act(def,s,"move_unit",10,Map.of("unitId",2,"positionX",150,"positionY",500,"facing",0));
        s=act(def,s,"end_activation",10,Map.of()); assertEquals("cleanup",s.phaseCode());
    }
    @Test
    void allowsTwoDistinctAttacksThenEditableMovementAndReset() {
        var def=definition(phase("engage",ALTERNATING_UNITS,1,List.of(
                new TurnStructure.Step("attack","Attack",List.of("attack_unit"),0,2,false,"arc"),move())));
        var s=act(def,state(),"start_game",0,Map.of());
        s=act(def,s,"attack_unit",10,Map.of("unitId",1,"targetUnitId",3));
        assertEquals("activating",s.units().getFirst().status());
        assertEquals("ACTION_SOURCE_ALREADY_USED",engine.applyAction(def,s,new Action("attack_unit",10,Map.of("unitId",1,"targetUnitId",3))).code());
        s=act(def,s,"move_unit",10,Map.of("unitId",1,"positionX",150,"positionY",100,"facing",90));
        assertFalse(engine.applyAction(def,s,new Action("attack_unit",10,Map.of("unitId",1,"targetUnitId",3))).accepted());
        s=act(def,s,"move_unit",10,Map.of("unitId",1,"positionX",160,"positionY",100,"facing",180));
        s=act(def,s,"reset_movement",10,Map.of("unitId",1));
        assertEquals(100,s.units().getFirst().positionX());
        assertEquals(1,ActivationContext.from(s).uses().get("attack"));
        assertFalse(engine.applyAction(def,s,new Action("end_activation",10,Map.of())).accepted());
    }
    @Test
    void twoDifferentActionSourcesShareOneActivationBudget() {
        var def=definition(phase("firing",ALTERNATING_UNITS,1,List.of(
                new TurnStructure.Step("shoot","Shoot",List.of("shoot"),0,2,false,"source"))));
        var sequence=new ActivationEngine(def,(s,a)-> {
            var events=new ArrayList<>(s.events()); events.add(new GameEvent(events.size()+1L,"shot",a.actorPlayerId(),a.parameters()));
            return new ActionResult(true,"OK","",new GameState(s.sessionId(),s.definitionVersionId(),s.status(),s.round(),s.phaseCode(),s.activePlayerId(),s.stateVersion()+1,
                    s.players(),s.units(),s.board(),events,s.values()));
        });
        var s=sequence.apply(state(),new Action("start_game",0,Map.of())).resultingState();
        s=sequence.apply(s,new Action("shoot",10,Map.of("unitId",1,"source","primary"))).resultingState();
        var second=sequence.apply(s,new Action("shoot",10,Map.of("unitId",1,"source","secondary")));
        assertTrue(second.accepted());
        assertEquals("ACTION_BUDGET_EXCEEDED",sequence.apply(second.resultingState(),new Action("shoot",10,Map.of("unitId",1,"source","third"))).code());
        assertEquals(20L,sequence.apply(second.resultingState(),new Action("end_activation",10,Map.of())).resultingState().activePlayerId());
    }
    @Test
    void groupActivationsAllowOneChoiceEachAndWholePlayerModeRetainsControl() {
        var choice=new TurnStructure.Step("choice","Action",List.of("move_unit","attack_unit"),0,1,true,null);
        for(var mode:List.of(ALTERNATING_UNITS,PLAYER_PHASE)) {
            var def=definition(phase("custom",mode,2,List.of(choice)));
            var s=act(def,state(),"start_game",0,Map.of());
            s=act(def,s,"attack_unit",10,Map.of("unitId",1,"targetUnitId",3));
            assertFalse(engine.applyAction(def,s,new Action("move_unit",10,Map.of("unitId",1,"positionX",150,"positionY",100,"facing",0))).accepted());
            s=act(def,s,"end_activation",10,Map.of()); assertEquals(10L,s.activePlayerId());
            s=act(def,s,"activate_unit",10,Map.of("unitId",2));
            s=act(def,s,"end_activation",10,Map.of()); assertEquals(20L,s.activePlayerId());
        }
    }
    @Test
    void alternatesGridActionsWithoutUnitActivationsOrMovement() {
        var def=definition(phase("grid_attack",ALTERNATING_ACTIONS,1,List.of(new TurnStructure.Step("fire","Fire",List.of("fire_cell"),1,1,false,null))));
        var sequencer=new ActivationEngine(def,(s,a)-> {
            var events=new ArrayList<>(s.events()); events.add(new GameEvent(events.size()+1L,"cell_fired",a.actorPlayerId(),a.parameters()));
            return new ActionResult(true,"OK","",new GameState(s.sessionId(),s.definitionVersionId(),s.status(),s.round(),s.phaseCode(),s.activePlayerId(),s.stateVersion()+1,
                    s.players(),s.units(),s.board(),events,s.values()));
        });
        var base=state();
        var grid=new GameState(base.sessionId(),base.definitionVersionId(),base.status(),base.round(),base.phaseCode(),base.activePlayerId(),base.stateVersion(),
                base.players(),List.of(),base.board(),base.events(),base.values());
        var s=sequencer.apply(grid,new Action("start_game",0,Map.of())).resultingState();
        assertFalse(sequencer.apply(s,new Action("move_unit",10,Map.of("unitId",1))).accepted());
        s=sequencer.apply(s,new Action("fire_cell",10,Map.of("cell","B4"))).resultingState(); assertEquals(20L,s.activePlayerId());
        s=sequencer.apply(s,new Action("fire_cell",20,Map.of("cell","A1"))).resultingState(); assertEquals(10L,s.activePlayerId()); assertEquals(2,s.round());
    }

    @Test
    void wholePlayerPhasesGiveUnitsSeparateBudgetsInTheNextPhase() {
        var def=definition(phase("maneuver_all",PLAYER_PHASE,1,List.of(move())),
                phase("shoot_all",PLAYER_PHASE,1,List.of(new TurnStructure.Step("fire","Fire",List.of("attack_unit"),0,1,false,null))));
        var s=act(def,state(),"start_game",0,Map.of());
        for (long id : List.of(1L,2L,3L)) {
            var unit=s.units().stream().filter(item->item.unitId()==id).findFirst().orElseThrow();
            s=act(def,s,"move_unit",unit.playerId(),Map.of("unitId",id,"positionX",unit.positionX()+10,"positionY",unit.positionY(),"facing",unit.facing()));
            s=act(def,s,"end_activation",unit.playerId(),Map.of());
        }
        assertEquals("shoot_all",s.phaseCode());
        assertEquals(10L,s.activePlayerId());
        assertTrue(ActivationContext.from(s).completedUnits().isEmpty());
        assertTrue(engine.legalActions(def,s).stream().anyMatch(action->action.type().equals("attack_unit")));
        assertFalse(engine.legalActions(def,s).stream().anyMatch(action->action.type().equals("move_unit")));
        s=act(def,s,"attack_unit",10,Map.of("unitId",1,"targetUnitId",3));
        assertEquals("activating",s.units().getFirst().status());
    }

    @Test
    void explicitStepAdvanceHonorsMinimumsAndSeparatesRepeatedActionTypes() {
        var def=definition(phase("custom",ALTERNATING_UNITS,1,List.of(
                new TurnStructure.Step("first","First move",List.of("move_unit"),1,1,false,null),
                new TurnStructure.Step("second","Second move",List.of("move_unit"),1,1,false,null))));
        var s=act(def,state(),"start_game",0,Map.of());
        s=act(def,s,"activate_unit",10,Map.of("unitId",1));
        assertFalse(engine.applyAction(def,s,new Action("advance_step",10,Map.of())).accepted());
        s=act(def,s,"move_unit",10,Map.of("unitId",1,"positionX",150,"positionY",100,"facing",0));
        assertFalse(engine.applyAction(def,s,new Action("end_activation",10,Map.of())).accepted());
        s=act(def,s,"advance_step",10,Map.of());
        s=act(def,s,"move_unit",10,Map.of("unitId",1,"positionX",175,"positionY",100,"facing",0));
        assertEquals(1,ActivationContext.from(s).uses().get("first"));
        assertEquals(1,ActivationContext.from(s).uses().get("second"));
        s=act(def,s,"end_activation",10,Map.of());
        assertEquals(20L,s.activePlayerId());
    }
}
