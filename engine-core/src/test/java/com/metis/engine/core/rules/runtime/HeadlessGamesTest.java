package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class HeadlessGamesTest {
    @TestFactory List<DynamicTest> completeGamesResumeAndReplayEveryCheckpoint() {
        return List.of("resource-race","hidden-grid").stream().map(name->DynamicTest.dynamicTest(name,()->{
            var runtime=runtime(example(name));var script=read("/rules/scenarios/"+name+"-play.json");
            var uninterrupted=RulesPlayCli.play(runtime,script,false);var resumed=RulesPlayCli.play(runtime,script,true);
            assertEquals(uninterrupted.state(),resumed.state());assertEquals(6,resumed.state().version());assertEquals(1,resumed.rejected());
            var restarted=resumed.initial();
            for(var command:script.get("steps")) {
                var recompiled=new RulesCompiler().compile(runtime.definition().canonicalJson());assertTrue(recompiled.valid());
                var freshRuntime=new RulesRuntime(recompiled.definition());restarted=freshRuntime.restore(RulesStateCodec.encode(restarted));
                var result=RulesPlayCli.submit(freshRuntime,restarted,command);
                if(command.has("expect"))assertEquals(command.get("expect").textValue(),result.code());else assertTrue(result.accepted(),result.code());
                if(result.accepted())restarted=result.state();
            }
            assertEquals(uninterrupted.state(),restarted,"A fresh compiler/runtime per transition must resume identically");
            var replay=resumed.initial();runtime.validateCheckpoint(replay);
            for(var commit:resumed.commits()) {
                replay=RulesRuntime.replay(replay,RulesStateCodec.decodeCommit(RulesStateCodec.encode(commit)));
                assertEquals(replay,runtime.restore(RulesStateCodec.encode(replay)));
            }
            assertEquals(resumed.state(),replay);assertEquals(List.of("alice"),replay.control().winners());
            var finalState=replay;rejected("GAME_NOT_RUNNING",runtime.pass(finalState,"alice",finalState.version()));
            assertEquals(List.of(),runtime.candidates(finalState,"alice"));
        })).toList();
    }
    @Test void changingOnlyGoalDataChangesTheNumberOfActionsNeeded() throws Exception {
        var versions=new ArrayList<Long>();
        for(int goal:List.of(2,5,6)) {
            var source=example("resource-race");((ObjectNode)source.at("/state/target")).put("initial",goal);
            var runtime=runtime(source);var state=start(runtime);
            for(int attempts=0;attempts<20&&state.control().status()!=RulesState.Status.FINISHED;attempts++) {
                String player=state.players().get(state.control().flow().playerIndex());var args=Map.of("counter",RuleValue.entity("counter",player));
                String action=runtime.preview(state,player,"boost",args).allowed()?"boost":"collect";state=accepted(runtime.act(state,player,action,args,state.version()));
            }
            assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals((long)goal,state.entities().get("alice").attributes().get("score").integer());versions.add(state.version());
        }
        assertEquals(List.of(2L,6L,8L),versions);
    }
    @Test void privateDecisionsAndEntityFieldsRemainPrivateAcrossResumeAndHistory() throws Exception {
        var runtime=runtime(example("hidden-grid"));var state=start(runtime);String decisionId=state.control().decision().id();String before=RulesStateCodec.encode(state);
        rejected("WRONG_RESPONDER",runtime.respond(state,"bob",decisionId,0,state.version()));
        rejected("STALE_DECISION",runtime.respond(state,"alice","old",0,state.version()));
        rejected("CHOICE_INVALID",runtime.respond(state,"alice",decisionId,-1,state.version()));
        rejected("CHOICE_INVALID",runtime.respond(state,"alice",decisionId,99,state.version()));
        rejected("CHOICE_INVALID",runtime.respond(state,"alice",decisionId,9,state.version()));
        rejected("DECISION_PENDING",runtime.pass(state,"alice",state.version()));assertEquals(before,RulesStateCodec.encode(state));
        assertEquals(9,RulesView.project(runtime.definition(),state,Set.of("alice")).decision().options().size());
        assertEquals("rules.decisionOpened",RulesView.project(runtime.definition(),state,Set.of("alice")).events().getLast().type());
        assertEquals("alice",RulesView.project(runtime.definition(),state,Set.of("alice")).events().getLast().recipient());
        assertTrue(RulesView.project(runtime.definition(),state,Set.of("bob")).decision().options().isEmpty());
        state=choose(runtime,state,0);assertEquals("bob",state.control().decision().player());
        assertEquals("A1",RulesView.project(runtime.definition(),state,Set.of("alice")).entities().get("alice").attributes().get("cell").value());
        assertFalse(RulesView.project(runtime.definition(),state,Set.of("bob")).entities().get("alice").attributes().containsKey("cell"));
        var restored=runtime.restore(RulesStateCodec.encode(state));state=choose(runtime,restored,8);
        for(var audience:List.of(Set.<String>of(),Set.of("not-a-seat"),Set.of("alice"),Set.of("bob"))) {
            var view=RulesView.project(runtime.definition(),state,audience);
            assertEquals(audience.contains("alice"),view.entities().get("alice").attributes().containsKey("cell"));
            assertEquals(audience.contains("bob"),view.entities().get("bob").attributes().containsKey("cell"));
            assertEquals(audience.size()==1&&!audience.contains("not-a-seat")?1:0,view.events().stream().filter(e->e.type().equals("rules.decisionResolved")).count());
            assertTrue(view.events().stream().noneMatch(e->e.type().equals("rules.entityCreated")));
        }
        rejected("NO_DECISION",runtime.respond(state,"alice",decisionId,0,state.version()));
    }
    @Test void rejectionAndPreviewDoNotAlterStateEventsOrVersion() throws Exception {
        var runtime=runtime(race());var state=start(runtime);String before=RulesStateCodec.encode(state);var args=Map.of("counter",RuleValue.entity("counter","alice"));
        assertTrue(runtime.preview(state,"alice","collect",args).allowed());assertEquals(2,runtime.candidates(state,"alice").size());
        assertEquals(List.of(),runtime.candidates(state,"bob"));assertEquals(List.of(),runtime.candidates(state,null));
        rejected("NOT_ACTIVE_PLAYER",runtime.act(state,"bob","collect",Map.of("counter",RuleValue.entity("counter","bob")),state.version()));
        rejected("PLAYER_UNKNOWN",runtime.act(state,null,"collect",args,state.version()));
        rejected("ACTION_UNKNOWN",runtime.act(state,"alice",null,args,state.version()));
        rejected("ARGUMENTS_INVALID",runtime.act(state,"alice","collect",Map.of(),state.version()));
        rejected("VALUE_TYPE",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.text("alice")),state.version()));
        rejected("ENTITY_NOT_FOUND",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","missing")),state.version()));
        rejected("ACTION_INELIGIBLE",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","bob")),state.version()));
        rejected("STALE_STATE",runtime.act(state,"alice","collect",args,state.version()-1));
        rejected("START_NOT_ALLOWED",runtime.start(state,state.version()));assertEquals(before,RulesStateCodec.encode(state));
        var result=runtime.act(state,"alice","collect",args,state.version());accepted(result);assertEquals(before,RulesStateCodec.encode(state));
        assertThrows(RuleEvaluationException.class,()->RulesRuntime.replay(result.state(),result.commit()));
    }
    @Test void aFailureAfterAnEffectRollsBackTheEntireSubmission() throws Exception {
        var source=race();body(source,"collect","""
          [{"op":"core.setState","field":"target","value":123},
           {"op":"core.modifyResource","entity":{"var":"counter"},"resource":"energy","delta":-3}]
          """);
        var runtime=runtime(source);var state=start(runtime);String before=RulesStateCodec.encode(state);
        rejected("RESOURCE_BOUNDS",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
        assertEquals(before,RulesStateCodec.encode(state));assertEquals(1000,state.globals().get("target").integer());
        assertEquals(2,state.entities().get("alice").resources().get("energy"));
    }
    @Test void paidEffectIsNotRepeatedAfterOpponentDecisionAndStackResume() throws Exception {
        var source=race();body(source,"collect","""
          [{"op":"core.modifyResource","entity":{"var":"counter"},"resource":"energy","delta":-1},
           {"op":"core.choose","player":{"op":"at","args":[{"var":"players"},1]},"options":{"list":{"elementType":"boolean","items":[true,false]}},"as":"answer","visibility":"PUBLIC"},
           {"op":"core.if","condition":{"var":"answer"},"then":[{"op":"core.call","procedure":"finish","args":{"actor":{"var":"actor"},"counter":{"var":"counter"}}}],"else":[]}]
          """);
        var runtime=runtime(source);var state=start(runtime);
        state=act(runtime,state,"alice","collect");assertEquals(1,state.entities().get("alice").resources().get("energy"));assertEquals("bob",state.control().decision().player());
        assertTrue(state.control().flow().progress().isEmpty());
        assertEquals(2,RulesView.project(runtime.definition(),state,Set.of()).decision().options().size());
        var pending=runtime.restore(RulesStateCodec.encode(state));state=choose(runtime,pending,0);
        assertEquals(1,state.entities().get("alice").resources().get("energy"));assertEquals(1,state.control().flow().progress().get("alice").uses().get("act"));
        assertEquals("bob",state.players().get(state.control().flow().playerIndex()));
    }
}
