package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.RuleValue;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePropertiesTest {
    @Property(tries=30,seed="20260922")
    void generatedGoalsPreservePreviewCommitResumeAndReplay(@ForAll @IntRange(min=1,max=12) int goal) throws Exception {
        var source=example("resource-race");((ObjectNode)source.at("/state/target")).put("initial",goal);
        var runtime=runtime(source);var initial=runtime.initial("generated",List.of("alice","bob"));
        var started=runtime.start(initial,0);var state=accepted(started);var replay=RulesRuntime.replay(initial,started.commit());
        for(int i=0;i<40&&state.control().status()!=RulesState.Status.FINISHED;i++) {
            String player=state.players().get(state.control().flow().playerIndex());var inputs=Map.of("counter",RuleValue.entity("counter",player));
            String action=runtime.preview(state,player,"boost",inputs).allowed()?"boost":"collect";
            assertTrue(runtime.preview(state,player,action,inputs).allowed());
            var result=runtime.act(state,player,action,inputs,state.version());state=runtime.restore(RulesStateCodec.encode(accepted(result)));
            replay=RulesRuntime.replay(replay,RulesStateCodec.decodeCommit(RulesStateCodec.encode(result.commit())));assertEquals(state,replay);
            assertTrue(state.entities().values().stream().allMatch(entity->entity.resources().get("energy")>=0));
        }
        assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals(List.of("alice"),state.control().winners());
        assertTrue(state.entities().get("alice").attributes().get("score").integer()>=goal);
    }
    @Property(tries=30,seed="20260923")
    void hiddenGridChoicesRemainPrivateAndGamesFinishForDifferentPlacements(@ForAll @IntRange(min=0,max=8) int alice,
                                                                                         @ForAll @IntRange(min=0,max=8) int bob) throws Exception {
        var runtime=runtime(example("hidden-grid"));var state=start(runtime);state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),alice);
        state=choose(runtime,runtime.restore(RulesStateCodec.encode(state)),bob);
        var spectator=RulesView.project(runtime.definition(),state,Set.of());assertTrue(spectator.entities().values().stream().noneMatch(entity->entity.attributes().containsKey("cell")));
        var cells=state.globals().get("cells").items();
        state=accepted(runtime.act(state,"alice","guess",Map.of("ownTarget",RuleValue.entity("target","alice"),"cell",cells.get((bob+1)%9)),state.version()));
        state=accepted(runtime.act(state,"bob","guess",Map.of("ownTarget",RuleValue.entity("target","bob"),"cell",cells.get((alice+1)%9)),state.version()));
        state=accepted(runtime.act(state,"alice","guess",Map.of("ownTarget",RuleValue.entity("target","alice"),"cell",cells.get(bob)),state.version()));
        assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals(List.of("alice"),state.control().winners());
        assertEquals(state,runtime.restore(RulesStateCodec.encode(state)));
    }
}
