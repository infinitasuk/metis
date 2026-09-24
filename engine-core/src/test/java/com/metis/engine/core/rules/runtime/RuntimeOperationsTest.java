package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeOperationsTest {
    @Test void nestedCallBranchAndLoopResumeWithIndependentLexicalScopes() throws Exception {
        var source=race();((ObjectNode)source.at("/entities/counter/resources/energy")).put("visibility","OWNER");
        ((ObjectNode)source.get("events")).set("privateChoice",JSON.readTree("{\"visibility\":\"OWNER\",\"fields\":{\"amount\":\"integer\"}}"));
        ((ObjectNode)source.get("events")).set("internal",JSON.readTree("{\"visibility\":\"SERVER\",\"fields\":{\"ref\":\"entity:counter\"}}"));
        ((ObjectNode)source.get("state")).set("secret",JSON.readTree("{\"type\":\"string\",\"initial\":\"SERVER_SENTINEL\",\"visibility\":\"SERVER\"}"));
        ((ObjectNode)source.get("procedures")).set("nested",JSON.readTree("""
          {"parameters":{"actor":"player","counter":"entity:counter"},"steps":[
            {"op":"core.if","condition":true,"then":[
              {"op":"core.forEach","source":{"list":{"elementType":"integer","items":[1,2]}},"as":"iteration","maxItems":2,"steps":[
                {"op":"core.choose","player":{"op":"at","args":[{"var":"players"},1]},"options":{"list":{"elementType":"integer","items":[1,2]}},"as":"choice","visibility":"OWNER"},
                {"op":"core.modifyResource","entity":{"var":"counter"},"resource":"energy","delta":{"var":"choice"}},
                {"op":"core.emit","event":"privateChoice","to":{"op":"at","args":[{"var":"players"},1]},"values":{"amount":{"var":"choice"}}}
              ]}
            ],"else":[]},
            {"op":"core.emit","event":"internal","values":{"ref":{"var":"counter"}}}
          ]}
          """));
        body(source,"collect","[{\"op\":\"core.call\",\"procedure\":\"nested\",\"args\":{\"actor\":{\"var\":\"actor\"},\"counter\":{\"var\":\"counter\"}}}]");
        var runtime=runtime(source);var state=act(runtime,start(runtime),"alice","collect");assertEquals(5,state.control().continuation().frames().size());
        state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),0);assertEquals(3,state.entities().get("alice").resources().get("energy"));
        assertEquals("bob",state.control().decision().player());state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),1);
        assertEquals(5,state.entities().get("alice").resources().get("energy"));assertEquals(1,state.control().flow().progress().get("alice").uses().get("act"));
        assertTrue(state.events().stream().filter(e->e.type().equals("rules.decisionResolved")).allMatch(e->e.actor().equals("bob")));
        var alice=RulesView.project(runtime.definition(),state,Set.of("alice"));var bob=RulesView.project(runtime.definition(),state,Set.of("bob"));
        assertTrue(alice.events().stream().noneMatch(e->e.type().equals("game.privateChoice")));assertEquals(2,bob.events().stream().filter(e->e.type().equals("game.privateChoice")).count());
        for(var view:List.of(alice,bob,RulesView.project(runtime.definition(),state,Set.of()))) {
            assertFalse(view.globals().containsKey("secret"));assertTrue(view.events().stream().noneMatch(e->e.type().equals("game.internal")));
        }
        assertFalse(bob.entities().get("alice").resources().containsKey("energy"));
    }
    @Test void unownedEntitiesOverridesRemovalAndElseEffectsFollowTheDefinition() throws Exception {
        var source=race();((ObjectNode)source.get("entities")).set("marker",JSON.readTree("{\"ownership\":\"NONE\",\"attributes\":{\"label\":{\"type\":\"string\",\"initial\":\"default\",\"visibility\":\"PUBLIC\"}},\"resources\":{\"secret\":{\"initial\":1,\"minimum\":0,\"maximum\":5,\"visibility\":\"SERVER\"}}}"));
        body(source,"collect","""
          [{"op":"core.createEntity","type":"marker","id":"neutral","attributes":{"label":"named"},"resources":{"secret":4},"as":"marker"},
           {"op":"core.if","condition":false,"then":[],"else":[{"op":"core.setState","field":"target","value":123}]},
           {"op":"core.choose","player":{"var":"actor"},"options":{"list":{"elementType":"boolean","items":[true]}},"as":"answer","visibility":"PUBLIC"},
           {"op":"core.removeEntity","entity":{"var":"marker"}}]
          """);
        var runtime=runtime(source);var state=act(runtime,start(runtime),"alice","collect");assertNull(state.entities().get("neutral").owner());
        assertEquals("named",state.entities().get("neutral").attributes().get("label").value());assertEquals(4,state.entities().get("neutral").resources().get("secret"));
        runtime.validateCheckpoint(state);assertEquals(Map.of(),RulesView.project(runtime.definition(),state,Set.of("alice")).entities().get("neutral").resources());
        state=choose(runtime,state,0);assertFalse(state.entities().containsKey("neutral"));assertEquals(123,state.globals().get("target").integer());
    }
    @Test void aDefinitionCanEndDuringSetupAndNoImplicitBattleVictoryRuns() throws Exception {
        var source=race();body(source,"prepare","[{\"op\":\"core.endGame\",\"outcome\":\"draw\",\"winners\":{\"list\":{\"elementType\":\"player\",\"items\":[]}}}]");
        var runtime=runtime(source);var state=start(runtime);assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals("draw",state.control().outcome());runtime.validateCheckpoint(state);
        source=race();body(source,"collect","[{\"op\":\"core.removeEntity\",\"entity\":{\"var\":\"counter\"}}]");runtime=runtime(source);state=act(runtime,start(runtime),"alice","collect");
        assertEquals(1,state.entities().size());assertEquals(RulesState.Status.RUNNING,state.control().status(),"No built-in elimination winner");
    }
    @Test void renamingGameEntityFieldsActionsAndPhasesDoesNotChangeExecution() throws Exception {
        String renamed=example("resource-race").toString().replace("resource-race","renamed-game").replace("\"counter\"","\"piece\"")
                .replace("entity:counter","entity:piece").replace("\"score\"","\"progress\"").replace("\"collect\"","\"gather\"").replace("\"turn\"","\"stage\"");
        String script=read("/rules/scenarios/resource-race-play.json").toString().replace("\"counter\"","\"piece\"").replace("\"collect\"","\"gather\"");
        var run=RulesPlayCli.play(runtime(JSON.readTree(renamed)),JSON.readTree(script),true);
        assertEquals(5,run.state().entities().get("alice").attributes().get("progress").integer());assertEquals("stage",run.state().control().flow().phase());
    }
    @Test void scriptDriverRejectsIncorrectOrIncompleteScenarios() throws Exception {
        var runtime=runtime(race());var state=start(runtime);
        assertThrows(IllegalArgumentException.class,()->RulesPlayCli.submit(runtime,state,JSON.readTree("{\"command\":\"wrong\"}")));
        assertThrows(IllegalArgumentException.class,()->RulesPlayCli.submit(runtime,state,JSON.readTree("{\"command\":\"respond\",\"actor\":\"alice\",\"choice\":1}")));
        assertThrows(IllegalArgumentException.class,()->RulesPlayCli.submit(runtime,state,JSON.readTree("{\"command\":\"act\",\"actor\":\"alice\",\"action\":\"missing\",\"args\":{}}")));
        assertThrows(IllegalArgumentException.class,()->RulesPlayCli.submit(runtime,state,JSON.readTree("{\"command\":\"act\",\"actor\":\"alice\",\"action\":\"collect\",\"args\":{\"wrong\":1}}")));
        var script=(ObjectNode)read("/rules/scenarios/resource-race-play.json");assertThrows(IllegalStateException.class,()->RulesPlayCli.play(runtime,script,false));
        ((ObjectNode)script.at("/steps/0")).put("expect","wrong");assertThrows(IllegalStateException.class,()->RulesPlayCli.play(runtime,script,false));
        var forbidden=JSON.readTree("{\"command\":\"act\",\"actor\":\"alice\",\"action\":\"collect\",\"args\":{\"counter\":\"alice\"}}");
        ((ArrayNode)script.get("steps")).removeAll().add(forbidden);assertThrows(IllegalStateException.class,()->RulesPlayCli.play(runtime,script,false));
        assertThrows(IllegalArgumentException.class,()->RulesPlayCli.main(new String[0]));
    }
    @Test void cliLoadsFilesAndRunsACompleteGame(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var definition=directory.resolve("game.json");var script=directory.resolve("script.json");
        java.nio.file.Files.writeString(definition,example("resource-race").toString());java.nio.file.Files.writeString(script,read("/rules/scenarios/resource-race-play.json").toString());
        var output=new java.io.ByteArrayOutputStream();var original=System.out;
        try { System.setOut(new java.io.PrintStream(output));RulesPlayCli.main(new String[]{definition.toString(),script.toString()}); }
        finally { System.setOut(original); }
        var result=JSON.readTree(output.toString());assertEquals("FINISHED",result.get("status").textValue());assertEquals(6,result.get("acceptedTransitions").intValue());
        java.nio.file.Files.writeString(definition,"{}");assertThrows(IllegalArgumentException.class,()->RulesPlayCli.main(new String[]{definition.toString(),script.toString()}));
    }
}
