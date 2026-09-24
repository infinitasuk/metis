package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeBoundariesTest {
    @Test void exactResourceEntityAndCollectionLimitsAreEnforced() throws Exception {
        var source=race();((ObjectNode)source.get("limits")).put("maxCallDepth",2);
        body(source,"collect","[{\"op\":\"core.modifyResource\",\"entity\":{\"var\":\"counter\"},\"resource\":\"energy\",\"delta\":18},{\"op\":\"core.call\",\"procedure\":\"finish\",\"args\":{\"actor\":{\"var\":\"actor\"},\"counter\":{\"var\":\"counter\"}}}]");
        var runtime=runtime(source);var state=act(runtime,start(runtime),"alice","collect");assertEquals(20,state.entities().get("alice").resources().get("energy"));
        source=race();((ObjectNode)source.get("limits")).put("maxCollectionSize",2);((ObjectNode)source.at("/procedures/prepare/steps/0")).put("maxItems",2);
        runtime=runtime(source);assertEquals(2,start(runtime).entities().size());
        ((ArrayNode)source.at("/procedures/prepare/steps")).add(JSON.readTree("{\"op\":\"core.createEntity\",\"type\":\"counter\",\"id\":\"extra\",\"owner\":{\"op\":\"at\",\"args\":[{\"var\":\"players\"},0]},\"attributes\":{},\"resources\":{},\"as\":\"extra\"}"));
        runtime=runtime(source);rejected("COLLECTION_LIMIT",runtime.start(runtime.initial("limit",List.of("alice","bob")),0));
        source=race();((ObjectNode)source.at("/actions/collect/parameters")).set("items",JSON.readTree("{\"list\":\"integer\"}"));runtime=runtime(source);state=start(runtime);
        var input=Map.of("counter",RuleValue.entity("counter","alice"),"items",RuleValue.list(RuleType.INTEGER,Collections.nCopies(32,RuleValue.integer(1))));accepted(runtime.act(state,"alice","collect",input,state.version()));
        rejected("COLLECTION_LIMIT",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice"),"items",RuleValue.list(RuleType.INTEGER,Collections.nCopies(33,RuleValue.integer(1)))),state.version()));
    }
    @Test void evenUnusedActionReferencesMustResolveBeforeExecution() throws Exception {
        for(var type:List.of("player","entity:counter")) {
            var source=race();((ObjectNode)source.at("/actions/collect/parameters")).put("unused",type);var runtime=runtime(source);var state=start(runtime);
            var ref=type.equals("player")?RuleValue.player("missing"):RuleValue.entity("counter","missing");
            rejected(type.equals("player")?"PLAYER_UNKNOWN":"ENTITY_NOT_FOUND",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice"),"unused",ref),state.version()));
        }
    }
    @Test void optionalStepDoesNotAllowAnActionOutsideItsMenu() throws Exception {
        var source=race();((ObjectNode)source.at("/flow/phases/turn/steps/0")).put("minimum",0).set("actions",JSON.readTree("[\"collect\"]"));
        var runtime=runtime(source);var state=start(runtime);rejected("ACTION_BUDGET",runtime.act(state,"alice","boost",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
    }
    @Test void anExhaustedPersistedBudgetAndAFullEventLedgerCannotAdvance() throws Exception {
        var runtime=runtime(example("hidden-grid"));var pending=start(runtime);var tree=(ObjectNode)JSON.readTree(RulesStateCodec.encode(pending));
        ((ObjectNode)tree.at("/control/continuation")).put("remainingBudget",0);pending=runtime.restore(tree.toString());
        rejected("EVALUATION_LIMIT",runtime.respond(pending,"alice",pending.control().decision().id(),0,pending.version()));
        runtime=runtime(entityPhase("ALTERNATING_ENTITIES",1));var state=start(runtime);var events=new ArrayList<RulesState.Event>();
        for(int i=1;i<=100000;i++)events.add(new RulesState.Event(i,"rules.started",null,CompiledRules.Audience.SERVER,null,Map.of(),"/setup"));
        var full=new RulesState(state.runtimeVersion(),state.definitionFingerprint(),state.sessionId(),state.version(),state.players(),state.globals(),state.entities(),state.control(),events);
        rejected("EVENT_LIMIT",runtime.activate(full,"alice","alice",full.version()));
    }
    @Test void executionLimitsCannotBeRenewedByAnsweringADecision() throws Exception {
        var source=race();((ObjectNode)source.get("limits")).put("maxInstructions",225);
        var steps=JSON.createArrayNode();for(int i=0;i<40;i++)steps.add(JSON.readTree("{\"op\":\"core.setState\",\"field\":\"target\",\"value\":1}"));
        steps.add(JSON.readTree("{\"op\":\"core.choose\",\"player\":{\"var\":\"actor\"},\"options\":{\"list\":{\"elementType\":\"integer\",\"items\":[0]}},\"as\":\"answer\",\"visibility\":\"PUBLIC\"}"));
        var loop=(ObjectNode)JSON.readTree("{\"op\":\"core.forEach\",\"source\":{\"list\":{\"elementType\":\"integer\",\"items\":[]}},\"as\":\"item\",\"maxItems\":32,\"steps\":[{\"op\":\"core.setState\",\"field\":\"target\",\"value\":1}]}");
        var items=(ArrayNode)loop.at("/source/list/items");for(int i=0;i<32;i++)items.add(i);steps.add(loop);((ObjectNode)source.at("/procedures/collect")).set("steps",steps);
        var runtime=runtime(source);var state=act(runtime,start(runtime),"alice","collect");assertNotNull(state.control().decision());
        assertTrue(state.control().continuation().remainingBudget()<225);String before=RulesStateCodec.encode(state);
        var restored=runtime.restore(before);rejected("EVALUATION_LIMIT",runtime.respond(restored,"alice",restored.control().decision().id(),0,restored.version()));assertEquals(before,RulesStateCodec.encode(restored));
    }
    @Test void invalidSetupLoopsChoicesAndEntityCreationAreAtomic() throws Exception {
        var source=race();((ObjectNode)source.at("/procedures/prepare/steps/0")).put("maxItems",1);var runtime=runtime(source);var initial=runtime.initial("limit",List.of("alice","bob"));
        rejected("LOOP_BOUND",runtime.start(initial,0));assertTrue(initial.entities().isEmpty());
        source=race();body(source,"prepare","[{\"op\":\"core.choose\",\"player\":{\"op\":\"at\",\"args\":[{\"var\":\"players\"},0]},\"options\":{\"list\":{\"elementType\":\"integer\",\"items\":[]}},\"as\":\"empty\",\"visibility\":\"PUBLIC\"}]");
        runtime=runtime(source);rejected("DECISION_EMPTY",runtime.start(runtime.initial("empty",List.of("alice")),0));
        source=race();((ObjectNode)source.at("/procedures/prepare/steps/0/steps/0")).put("id","duplicate");runtime=runtime(source);rejected("ENTITY_ID",runtime.start(runtime.initial("duplicate",List.of("alice","bob")),0));
        source=race();((ObjectNode)source.at("/procedures/prepare/steps/0/steps/0")).put("id","");runtime=runtime(source);rejected("ENTITY_ID",runtime.start(runtime.initial("blank",List.of("alice")),0));
    }
    @Test void resourceAndNumericOverflowAreRejectedBeforeAnyCommit() throws Exception {
        var source=race();body(source,"collect","[{\"op\":\"core.modifyResource\",\"entity\":{\"var\":\"counter\"},\"resource\":\"energy\",\"delta\":9223372036854775807}]");
        var runtime=runtime(source);var state=start(runtime);rejected("NUMERIC_ERROR",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
        source=race();((ObjectNode)source.at("/procedures/prepare/steps/0/steps/0/resources")).put("energy",21);runtime=runtime(source);rejected("RESOURCE_BOUNDS",runtime.start(runtime.initial("bounds",List.of("alice")),0));
        runtime=runtime(race());state=start(runtime);
        var maximum=new RulesState(state.runtimeVersion(),state.definitionFingerprint(),state.sessionId(),Long.MAX_VALUE,state.players(),state.globals(),state.entities(),state.control(),state.events());
        rejected("NUMERIC_ERROR",runtime.act(maximum,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),Long.MAX_VALUE));
    }
    @Test void invalidIdentitiesReferencesAndRuntimeVersionsFailExplicitly() throws Exception {
        var runtime=runtime(race());
        assertThrows(RuleEvaluationException.class,()->runtime.initial(null,List.of("alice")));
        assertThrows(RuleEvaluationException.class,()->runtime.initial("",List.of("alice")));
        assertThrows(RuleEvaluationException.class,()->runtime.initial("s".repeat(4001),List.of("alice")));
        assertThrows(RuleEvaluationException.class,()->runtime.initial("s",List.of()));
        assertThrows(RuleEvaluationException.class,()->runtime.initial("s",List.of("alice","alice")));
        assertThrows(RuleEvaluationException.class,()->runtime.initial("s",Arrays.asList("alice",null)));
        assertThrows(RuleEvaluationException.class,()->runtime.initial("s",List.of("")));
        assertThrows(RuleEvaluationException.class,()->runtime.initial("s",java.util.stream.IntStream.range(0,33).mapToObj(i->"p"+i).toList()));
        rejected("STATE_REQUIRED",runtime.start(null,0));
        var state=start(runtime);var source=race();((ObjectNode)source.at("/state/target")).put("initial",999);var other=runtime(source);
        rejected("SNAPSHOT_INCOMPATIBLE",other.pass(state,"alice",state.version()));assertThrows(IllegalArgumentException.class,()->RulesView.project(other.definition(),state,Set.of()));
        assertThrows(RuleEvaluationException.class,()->other.restore(RulesStateCodec.encode(state)));
        var wrong=new RulesState("future",state.definitionFingerprint(),state.sessionId(),state.version(),state.players(),state.globals(),state.entities(),state.control(),state.events());
        rejected("SNAPSHOT_INCOMPATIBLE",runtime.pass(wrong,"alice",wrong.version()));
        rejected("ENTITY_PHASE_REQUIRED",runtime.activate(state,"alice","alice",state.version()));
        assertThrows(RuleEvaluationException.class,()->runtime.eligibleEntities(state,"alice"));
        assertFalse(runtime.preview(state,"bob","collect",Map.of()).allowed());assertFalse(runtime.preview(state,"alice","missing",Map.of()).allowed());
    }
    @Test void selectedEntityRemovalEndsItsActivationWithoutGrantingAnotherSubjectItsBudget() throws Exception {
        var source=entityPhase("ALTERNATING_ENTITIES",1);((ObjectNode)source.at("/flow/phases/turn/steps/0")).put("minimum",2).put("maximum",2);
        body(source,"collect","[{\"op\":\"core.removeEntity\",\"entity\":{\"var\":\"counter\"}}]");var runtime=runtime(source);var state=start(runtime);
        state=accepted(runtime.activate(state,"alice","alice",state.version()));state=act(runtime,state,"alice","collect");
        assertFalse(state.entities().containsKey("alice"));assertEquals(1,state.control().flow().playerIndex());assertNull(state.control().flow().entity());runtime.validateCheckpoint(state);
    }
    @Test void onlyInstalledCoreEffectsExecuteAndDuplicateWinnerListsAreInvalid() throws Exception {
        var source=race();((ObjectNode)source.get("modules")).put("custom","1");body(source,"collect","[{\"op\":\"custom.effect\",\"args\":{}}]");
        var registry=new OperationRegistry(List.of(new OperationRegistry.Module("custom","1",List.of(OperationRegistry.Operation.mechanical("custom.effect",Map.of(),null,Set.of(OperationRegistry.Effect.STATE),OperationRegistry.Preview.NONE)))));
        var compiled=new RulesCompiler(registry).compile(source.toString());assertTrue(compiled.valid());assertThrows(RuleEvaluationException.class,()->new RulesRuntime(compiled.definition()));
        source=race();body(source,"collect","[{\"op\":\"core.endGame\",\"outcome\":\"win\",\"winners\":{\"list\":{\"elementType\":\"player\",\"items\":[{\"var\":\"actor\"},{\"var\":\"actor\"}]}}}]");
        var runtime=runtime(source);var state=start(runtime);rejected("WINNER_DUPLICATE",runtime.act(state,"alice","collect",Map.of("counter",RuleValue.entity("counter","alice")),state.version()));
    }
    @Test void oversizedResultCannotCreateAnUncheckpointableAcceptedState() throws Exception {
        var source=race();((ObjectNode)source.get("limits")).put("maxInstructions",100000).put("maxCollectionSize",1024);
        ((ObjectNode)source.at("/procedures/prepare/steps/0")).put("maxItems",300);
        var attributes=(ObjectNode)source.at("/entities/counter/attributes");
        for(int i=0;i<16;i++)attributes.putObject("large"+i).put("type","string").put("initial","x".repeat(4096)).put("visibility","PUBLIC");
        var runtime=runtime(source);var initial=runtime.initial("large",java.util.stream.IntStream.range(0,300).mapToObj(i->"p"+i).toList());
        rejected("CHECKPOINT_LIMIT",runtime.start(initial,0));assertTrue(initial.entities().isEmpty());
    }
}
