package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialConformanceTest {
    static RuleValue decimal(double n) { return RuleValue.decimal(BigDecimal.valueOf(n)); }
    static RuleValue path(double... coordinates) {
        var points=new ArrayList<RuleValue>();for(int i=0;i<coordinates.length;i+=2)points.add(RuleValue.list(RuleType.DECIMAL,List.of(decimal(coordinates[i]),decimal(coordinates[i+1]))));
        return RuleValue.list(RuleType.list(RuleType.DECIMAL),points);
    }
    static Map<String,RuleValue> move(String type,String id,double heading,double... points) { return Map.of("entity",RuleValue.entity(type,id),"heading",decimal(heading),"path",path(points)); }
    static RulesState apply(RulesRuntime runtime,RulesState state,RulesCpu.Command command) {
        return accepted(switch(command.type()) {
            case "act"->runtime.act(state,command.actor(),command.action(),command.parameters(),command.expectedVersion());
            case "respond"->runtime.respond(state,command.actor(),command.decision(),command.option(),command.expectedVersion());
            case "activate"->runtime.activate(state,command.actor(),command.entity(),command.expectedVersion());
            case "advanceStep"->runtime.advanceStep(state,command.actor(),command.expectedVersion());
            default->runtime.pass(state,command.actor(),command.expectedVersion());
        });
    }
    static RulesState play(String name) throws Exception {
        var source=example(name);var runtime=runtime(source);var initial=runtime.definition().modules().containsKey("dice")?runtime.initial(name,List.of("alice","bob"),RandomStream.seeded("12".repeat(32))):runtime.initial(name,List.of("alice","bob"));
        var started=runtime.start(initial,0);var state=accepted(started);assertEquals(state,RulesRuntime.replay(initial,started.commit()));var cpu=new RulesCpu();
        int moves=0,reactions=0;var activations=new ArrayList<String>();
        for(int i=0;i<300&&state.control().status()!=RulesState.Status.FINISHED;i++) {
            runtime=runtime(source);state=runtime.restore(RulesStateCodec.encode(state));RulesCpu.Command command=null;
            for(String actor:state.players()) { var selected=cpu.choose(runtime.decisionInput(state,actor));if(selected.isPresent()) { command=selected.get();break; } }
            assertNotNull(command,"No offered command at "+state.control());
            if(command.type().equals("activate"))activations.add(command.actor());
            if(command.type().equals("respond"))reactions++;
            if(command.type().equals("act")&&command.action().equals("move")) {
                var preview=runtime.preview(state,command.actor(),command.action(),command.parameters());assertTrue(preview.allowed(),preview.code());assertFalse(preview.movements().isEmpty());moves++;
                var before=RulesStateCodec.encode(state);var next=apply(runtime,state,command);assertEquals(before,RulesStateCodec.encode(state));
                var movement=preview.movements().getFirst();var body=runtime.definition().space().bodies().get(next.entities().get(movement.entity()).type());
                assertEquals(movement.to().x(),next.entities().get(movement.entity()).attributes().get(body.x()).number().doubleValue());
            }
            var result=switch(command.type()) {
                case "act"->runtime.act(state,command.actor(),command.action(),command.parameters(),state.version());
                case "respond"->runtime.respond(state,command.actor(),command.decision(),command.option(),state.version());
                case "activate"->runtime.activate(state,command.actor(),command.entity(),state.version());
                case "advanceStep"->runtime.advanceStep(state,command.actor(),state.version());
                default->runtime.pass(state,command.actor(),state.version());
            };
            var next=accepted(result);assertEquals(next,RulesRuntime.replay(state,RulesStateCodec.decodeCommit(RulesStateCodec.encode(result.commit()))));state=next;
        }
        assertEquals(RulesState.Status.FINISHED,state.control().status());
        if(!name.equals("hidden-grid-cpu")) { assertTrue(moves>0);assertTrue(reactions>0);assertEquals("elimination",state.control().outcome()); }
        if(name.equals("grid-skirmish"))assertEquals(List.of("alice","alice","bob","bob"),activations.subList(0,4));
        return state;
    }
    @Test void threeCompleteGamesUseOneProjectionBoundCpuAndReplayEveryCommit() throws Exception {
        for(String name:List.of("grid-skirmish","vector-orders","hidden-grid-cpu"))assertFalse(play(name).control().winners().isEmpty());
    }
    @Test void gridPreviewAndCommitRejectIdenticalInvalidPathsAndKeepInputUntouched() throws Exception {
        var runtime=runtime(example("grid-skirmish"));var state=start(runtime);state=accepted(runtime.activate(state,"alice","alice-a",state.version()));String original=RulesStateCodec.encode(state);
        var cases=List.of(new Object[]{move("piece","alice-a",0,3,1),"GRID_PATH"},new Object[]{move("piece","alice-a",0,1,2,1,3),"MOVEMENT_COLLISION"},new Object[]{move("piece","alice-a",0,0,1,-1,1),"BOARD_BOUNDS"},new Object[]{move("piece","alice-a",0,1.5,1),"GRID_POSITION"},new Object[]{move("piece","alice-a",0,2,1,3,1,4,1),"MOVEMENT_DISTANCE"},new Object[]{move("piece","alice-a",0),"PATH_INVALID"});
        for(var item:cases) { @SuppressWarnings("unchecked") var parameters=(Map<String,RuleValue>)item[0];var preview=runtime.preview(state,"alice","move",parameters);assertFalse(preview.allowed());assertEquals(item[1],preview.code());rejected((String)item[1],runtime.act(state,"alice","move",parameters,state.version()));assertEquals(original,RulesStateCodec.encode(state)); }
        var params=move("piece","alice-a",0,2,1,3,1);assertTrue(runtime.preview(state,"alice","move",params).allowed());var next=accepted(runtime.act(state,"alice","move",params,state.version()));assertEquals(3,next.entities().get("alice-a").attributes().get("x").integer());rejected("ACTION_BUDGET",runtime.act(next,"alice","move",params,next.version()));rejected("STALE_STATE",runtime.act(next,"alice","move",params,state.version()));
        assertNull(state.control().mechanics());assertEquals("rules-runtime-4",runtime.version());assertEquals(next,runtime.restore(RulesStateCodec.encode(next)));
    }
    @Test void hiddenEnemyPositionsDoNotChangeOffersOrCpuDecision() throws Exception {
        var runtime=runtime(example("hidden-grid-cpu"));var base=start(runtime);base=choose(runtime,base,8);var left=choose(runtime,base,0);var right=choose(runtime,base,7);
        var a=runtime.decisionInput(left,"alice");var b=runtime.decisionInput(right,"alice");assertEquals(a,b);assertEquals(new RulesCpu().choose(a),new RulesCpu().choose(b));
        assertFalse(a.view().entities().get("bob").attributes().containsKey("cell"));assertEquals(9,a.actions().size());
        var unknown=new RulesCpu.Input("bob",a.view(),List.of(),List.of(),List.of());assertTrue(new RulesCpu().choose(unknown).isEmpty());
    }
    @Test void scoringCannotBeSkippedByPassingTheLastActivation() throws Exception {
        var source=example("grid-skirmish");((ObjectNode)source.at("/state/roundLimit")).put("initial",1);var runtime=runtime(source);var state=start(runtime);
        for(String id:List.of("alice-a","alice-b","bob-a","bob-b")) { String actor=id.substring(0,id.indexOf('-'));state=accepted(runtime.activate(state,actor,id,state.version()));state=accepted(runtime.pass(state,actor,state.version())); }
        assertEquals("objective",state.control().outcome());assertEquals(List.of("alice","bob"),state.control().winners());assertEquals(2,state.events().stream().filter(e->e.type().equals("game.score")).count());
    }
    @Test void cliCompletesAndItsOperationalCapNeverInventsAVictory() throws Exception {
        for(String name:List.of("grid-skirmish","vector-orders","hidden-grid-cpu"))assertFalse(RulesCpuCli.play(example(name).toString(),300).winners().isEmpty());
        assertThrows(IllegalStateException.class,()->RulesCpuCli.play(example("grid-skirmish").toString(),1));assertThrows(IllegalArgumentException.class,()->RulesCpuCli.play("{}",300));assertThrows(IllegalArgumentException.class,()->RulesCpuCli.play("{}",0));assertThrows(IllegalArgumentException.class,()->RulesCpuCli.main(new String[]{}));
    }
    @Test void blindCpuCompletesEveryEnemyCellWithoutRepeatingGuesses() throws Exception {
        var runtime=runtime(example("hidden-grid-cpu"));var cpu=new RulesCpu();
        for(int cell=0;cell<9;cell++) {
            var state=choose(runtime,choose(runtime,start(runtime),8),cell);
            for(int n=0;n<18&&state.control().status()!=RulesState.Status.FINISHED;n++) {
                String actor=state.players().get(state.control().flow().playerIndex());var command=cpu.choose(runtime.decisionInput(state,actor)).orElseThrow();state=apply(runtime,state,command);
                var guesses=state.entities().get(actor).attributes().get("guesses").items();assertEquals(guesses.size(),new HashSet<>(guesses).size());
            }
            assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals(List.of("alice"),state.control().winners());
        }
    }
}
