package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class NavigationConformanceTest {
    static ObjectNode source() throws Exception {
        var parent=read("/rules/catalogue/armada-ship-maneuvers.json");var pack=read("/rules/catalogue/armada-ship-navigation.pack.json");var compiled=new RulesCompiler().compile(parent.toString());assertTrue(compiled.valid());
        var mode=JSON.createObjectNode().put("modeVersion",1).put("id",pack.get("definition").textValue()).put("revision","test");
        mode.putObject("extends").put("id",compiled.definition().id()).put("revision",compiled.definition().revision()).put("fingerprint",compiled.definition().fingerprint());mode.set("overrides",pack.get("overrides"));
        var resolved=new RulesResolver().resolve(mode.toString(),ref->parent.toString());assertTrue(resolved.valid(),resolved.diagnostics().toString());return (ObjectNode)JSON.readTree(resolved.definition().canonicalJson());
    }
    static String id(String actor) { return actor.equals("alice")?"seat0-ship0":"seat1-ship0"; }
    static RuleValue entity(String actor) { return RuleValue.entity("ship",id(actor)); }
    static RuleValue orders(String... values) { return RuleValue.list(RuleType.STRING,Arrays.stream(values).map(RuleValue::text).toList()); }
    static Map<String,RuleValue> move(String actor,int speed,int... yaw) { return Map.of("entity",entity(actor),"speed",RuleValue.integer(speed),"side",RuleValue.text("PORT"),"yaw",RuleValue.list(RuleType.INTEGER,Arrays.stream(yaw).mapToObj(RuleValue::integer).toList())); }
    static RulesState deploy(RulesRuntime runtime) {
        var state=accepted(runtime.start(runtime.initial("navigation",List.of("alice","bob"),RandomStream.tape(0)),0));
        for(String actor:List.of("alice","bob")) {
            state=accepted(runtime.activate(state,actor,id(actor),state.version()));var offer=runtime.offeredActions(state,actor).getFirst();
            state=accepted(runtime.act(state,actor,offer.action(),offer.parameters(),state.version()));
        }
        assertEquals("planning",state.control().flow().phase());assertEquals(1,state.control().flow().round());return state;
    }
    static RulesState plan(RulesRuntime runtime,RulesState state,String actor,String... commands) { return accepted(runtime.act(state,actor,"planOrders",Map.of("entity",entity(actor),"orders",orders(commands)),state.version())); }
    static RulesState plans(RulesRuntime runtime,RulesState state) { return plan(runtime,plan(runtime,state,"alice","navigate"),"bob","navigate"); }
    static RulesState reveal(RulesRuntime runtime,RulesState state,String actor) {
        state=accepted(runtime.activate(state,actor,id(actor),state.version()));return accepted(runtime.act(state,actor,"revealCommand",Map.of("entity",entity(actor)),state.version()));
    }
    static RulesState disposition(RulesRuntime runtime,RulesState state,String choice) { var d=state.control().decision();assertNotNull(d);int index=d.options().indexOf(RuleValue.text(choice));assertTrue(index>=0);return accepted(runtime.respond(state,d.player(),d.id(),index,state.version())); }
    static RulesState ready(RulesRuntime runtime,String choice) { return disposition(runtime,reveal(runtime,plans(runtime,deploy(runtime)),"alice"),choice); }
    static RulesState act(RulesRuntime runtime,RulesState state,String actor,String action,int speed,int... yaw) { return accepted(runtime.act(state,actor,action,move(actor,speed,yaw),state.version())); }
    static long token(RulesState state,String name) { return state.entities().get(id("alice")).resources().get(name); }
    static long speed(RulesState state) { return state.entities().get(id("alice")).attributes().get("speed").integer(); }
    static RulesState bobCompletesRound(RulesRuntime runtime,RulesState state) {
        state=reveal(runtime,state,"bob");if(state.control().decision()!=null)state=disposition(runtime,state,"keepDial");
        state=act(runtime,state,"bob","maneuver",2,0,0);return runtime.controls(state,"bob").contains("pass")?accepted(runtime.pass(state,"bob",state.version())):state;
    }
    static RulesState bankedNextRound(RulesRuntime runtime) {
        var state=act(runtime,ready(runtime,"bankToken"),"alice","maneuver",2,0,0);if(runtime.controls(state,"alice").contains("pass"))state=accepted(runtime.pass(state,"alice",state.version()));state=bobCompletesRound(runtime,state);
        assertEquals(1,token(state,"navigateToken"));assertEquals(2,state.control().flow().round());return plans(runtime,state);
    }
    static void reject(RulesRuntime runtime,RulesState state,String action,Map<String,RuleValue> p,String code,String detail) {
        String before=RulesStateCodec.encode(state);var preview=runtime.preview(state,"alice",action,p);assertFalse(preview.allowed());assertEquals(code,preview.code(),preview.message());assertTrue(preview.message().contains(detail),preview.message());
        var result=runtime.act(state,"alice",action,p,state.version());rejected(code,result);assertTrue(result.message().contains(detail),result.message());assertEquals(before,RulesStateCodec.encode(state));
    }
    @Test void deploymentRequiresPrintedStartingSpeedAndCapturesCommandCapacity() throws Exception {
        var runtime=runtime(source());var state=start(runtime);state=accepted(runtime.activate(state,"alice",id("alice"),state.version()));
        var p=new TreeMap<>(runtime.offeredActions(state,"alice").getFirst().parameters());String before=RulesStateCodec.encode(state);
        for(int invalid:List.of(0,5,-1)) { p.put("speed",RuleValue.integer(invalid));var result=runtime.act(state,"alice","deploy",p,state.version());rejected("SCENARIO_REQUIREMENT",result);assertTrue(result.message().contains("DEPLOY_SPEED"));assertEquals(before,RulesStateCodec.encode(state)); }
        for(int legal=1;legal<=4;legal++) { p.put("speed",RuleValue.integer(legal));assertEquals(legal,speed(accepted(runtime.act(state,"alice","deploy",p,state.version())))); }
        assertEquals(1,state.entities().get(id("alice")).attributes().get("commandValue").integer());assertEquals(List.of(RuleValue.integer(1),RuleValue.integer(2),RuleValue.integer(2)),state.entities().get(id("alice")).attributes().get("dialChart").items().get(3).items());
    }
    @Test void privatePlansDoNotAffectOpponentProjectionOrCpuAndCannotBeRewritten() throws Exception {
        var runtime=runtime(source());var state=deploy(runtime);var left=plan(runtime,state,"bob","repair");var right=plan(runtime,state,"bob","squadron");
        assertEquals(RulesView.project(runtime.definition(),left,Set.of("alice")),RulesView.project(runtime.definition(),right,Set.of("alice")));
        assertEquals(runtime.decisionInput(left,"alice"),runtime.decisionInput(right,"alice"));assertEquals(new RulesCpu().choose(runtime.decisionInput(left,"alice")),new RulesCpu().choose(runtime.decisionInput(right,"alice")));
        for(var viewers:List.of(Set.<String>of(),Set.of("alice")))assertFalse(RulesView.project(runtime.definition(),left,viewers).entities().get(id("bob")).attributes().containsKey("orders"));
        assertEquals(orders("repair"),RulesView.project(runtime.definition(),left,Set.of("bob")).entities().get(id("bob")).attributes().get("orders"));
        rejected("NOT_ACTIVE_PLAYER",runtime.act(left,"bob","planOrders",Map.of("entity",entity("bob"),"orders",orders("navigate")),left.version()));
        rejected("ACTION_INELIGIBLE",runtime.act(state,"alice","planOrders",Map.of("entity",entity("bob"),"orders",orders("navigate")),state.version()));
        rejected("ORDER_UNKNOWN",runtime.act(state,"alice","planOrders",Map.of("entity",entity("alice"),"orders",orders("unknown")),state.version()));
    }
    @Test void revealIsMandatoryAndCurrentSpeedPersistsWithoutNavigate() throws Exception {
        var runtime=runtime(source());var state=deploy(runtime);state=plan(runtime,plan(runtime,state,"alice","repair"),"bob","squadron");
        state=accepted(runtime.activate(state,"alice",id("alice"),state.version()));rejected("ACTION_BUDGET",runtime.act(state,"alice","maneuver",move("alice",2,0,0),state.version()));
        state=accepted(runtime.act(state,"alice","revealCommand",Map.of("entity",entity("alice")),state.version()));assertNull(state.control().decision());assertEquals(0,token(state,"navigateDial"));
        reject(runtime,state,"maneuver",move("alice",3,0,0,0),"SCENARIO_REQUIREMENT","NAVIGATE_SPEED");
        assertEquals(List.of(2),runtime.candidates(state,"alice").stream().filter(c->c.action().equals("maneuver")).findFirst().orElseThrow().maneuver().speeds());
        state=bobCompletesRound(runtime,act(runtime,state,"alice","maneuver",2,0,0));assertEquals(2,speed(state));
        assertTrue(state.events().stream().anyMatch(e->e.type().equals("game.commandDeclined")&&e.values().get("command").equals(RuleValue.text("repair"))));
    }
    @Test void dialChangesSpeedAndGrantsOnlyOneCappedYawBonus() throws Exception {
        var runtime=runtime(source());var state=ready(runtime,"keepDial");
        reject(runtime,state,"navigateDial",move("alice",4,0,0,0,0),"SCENARIO_REQUIREMENT","NAVIGATE_SPEED");
        reject(runtime,state,"navigateDial",move("alice",3,1,2,0),"SCENARIO_REQUIREMENT","NAVIGATE_YAW_BONUS");
        reject(runtime,state,"navigateDial",move("alice",3,2,0,0),"MANEUVER_YAW","");reject(runtime,state,"navigateDial",move("alice",3,0,0,3),"MANEUVER_YAW","");
        var preview=runtime.preview(state,"alice","navigateDial",move("alice",3,1,0,0));assertTrue(preview.allowed(),preview.message());assertEquals(List.of(new RulesRuntime.ResourceChange(id("alice"),"navigateDial",1,0)),preview.resources());
        var next=act(runtime,state,"alice","navigateDial",3,1,0,0);assertEquals(3,speed(next));assertEquals(0,token(next,"navigateDial"));assertEquals(preview.movements().getFirst().to().heading(),next.entities().get(id("alice")).attributes().get("heading").number().doubleValue());
        assertEquals(1,next.control().mechanics().uses().values().stream().filter(u->u.key().equals("navigate")&&u.period().equals("ROUND")).count());
    }
    @Test void tokenCanBeGainedAndSpentInSameActivationButDoesNotGrantYaw() throws Exception {
        var runtime=runtime(source());var state=ready(runtime,"bankToken");assertEquals(0,token(state,"navigateDial"));assertEquals(1,token(state,"navigateToken"));
        reject(runtime,state,"navigateDial",move("alice",2,0,0),"ACTION_INELIGIBLE","");reject(runtime,state,"navigateToken",move("alice",3,1,0,0),"MANEUVER_YAW","");
        var next=act(runtime,state,"alice","navigateToken",1,0);assertEquals(1,speed(next));assertEquals(0,token(next,"navigateToken"));
        assertEquals(0,next.control().mechanics().random().position());
    }
    @Test void bankedTokensPersistAndDuplicateGainsDiscardOnlyTheNewToken() throws Exception {
        var runtime=runtime(source());var state=bankedNextRound(runtime);state=disposition(runtime,reveal(runtime,state,"alice"),"bankToken");
        assertEquals(1,token(state,"navigateToken"));assertEquals(0,token(state,"navigateDial"));
        var events=state.events().stream().filter(e->e.type().equals("game.navigateTokenBanked")).toList();assertEquals(2,events.size());assertFalse(events.getFirst().values().get("duplicateDiscarded").bool());assertTrue(events.getLast().values().get("duplicateDiscarded").bool());
        reject(runtime,state,"navigateDialAndToken",move("alice",4,0,0,0,0),"ACTION_INELIGIBLE","");
    }
    @Test void combinedPaymentIsAtomicSupportsStoppingAndReplaysExactly() throws Exception {
        var runtime=runtime(source());var state=disposition(runtime,reveal(runtime,bankedNextRound(runtime),"alice"),"keepDial");String before=RulesStateCodec.encode(state);
        for(int speed:List.of(0,4)) {
            var p=move("alice",speed,new int[speed]);var preview=runtime.preview(state,"alice","navigateDialAndToken",p);assertTrue(preview.allowed(),preview.message());assertEquals(2,preview.resources().size());assertEquals(before,RulesStateCodec.encode(state));
            var result=runtime.act(runtime.restore(before),"alice","navigateDialAndToken",p,state.version());var next=accepted(result);assertEquals(speed,speed(next));assertEquals(0,token(next,"navigateDial"));assertEquals(0,token(next,"navigateToken"));
            assertEquals(next,runtime.restore(RulesStateCodec.encode(next)));assertEquals(next,RulesRuntime.replay(state,RulesStateCodec.decodeCommit(RulesStateCodec.encode(result.commit()))));
            assertEquals(1,next.control().mechanics().uses().values().stream().filter(u->u.entity().equals(id("alice"))&&u.key().equals("navigate")).count());
            rejected("STALE_STATE",runtime.act(next,"alice","navigateDialAndToken",p,state.version()));
            if(speed==0) { assertEquals(0,preview.movements().getFirst().distance());assertEquals(state.entities().get(id("alice")).attributes().get("x"),next.entities().get(id("alice")).attributes().get("x")); }
        }
    }
    @Test void noEffectResolutionStillConsumesPaymentAndOncePerRoundAllowance() throws Exception {
        var source=source();((ObjectNode)source.at("/flow/phases/navigation/steps/1")).put("maximum",2);var runtime=runtime(source);
        var state=disposition(runtime,reveal(runtime,bankedNextRound(runtime),"alice"),"keepDial");state=act(runtime,state,"alice","navigateDial",2,0,0);
        assertEquals(2,speed(state));assertEquals(1,token(state,"navigateToken"));reject(runtime,state,"navigateToken",move("alice",1,0),"ACTION_INELIGIBLE","");
        ((ObjectNode)source.at("/actions/navigateToken")).put("availableWhen",true);var unrestricted=runtime(source);var other=disposition(unrestricted,reveal(unrestricted,bankedNextRound(unrestricted),"alice"),"keepDial");other=act(unrestricted,other,"alice","navigateDial",2,0,0);
        reject(unrestricted,other,"navigateToken",move("alice",1,0),"RESOURCE_ALREADY_USED","");
    }
    @Test void invalidGeometryRollsBackTheDialAndUsageMarker() throws Exception {
        var source=source();((ObjectNode)source.at("/state/startX")).set("initial",JSON.readTree("[69,60]"));((ObjectNode)source.at("/space/zones/west")).put("width",72);
        var runtime=runtime(source);var state=ready(runtime,"keepDial");reject(runtime,state,"navigateDial",move("alice",2,0,0),"BOARD_BOUNDS","");
        assertEquals(1,token(state,"navigateDial"));assertTrue(state.control().mechanics().uses().isEmpty());
    }
    @Test void fifoRefillPreservesTheCommittedPrefixAcrossRounds() throws Exception {
        var source=source();((ObjectNode)source.at("/state/commandRatings")).set("initial",JSON.readTree("[2,1]"));var runtime=runtime(source);
        var state=plan(runtime,plan(runtime,deploy(runtime),"alice","repair","navigate"),"bob","squadron");state=reveal(runtime,state,"alice");assertNull(state.control().decision());
        state=bobCompletesRound(runtime,act(runtime,state,"alice","maneuver",2,0,0));String before=RulesStateCodec.encode(state);
        rejected("QUEUE_CAPACITY",runtime.act(state,"alice","planOrders",Map.of("entity",entity("alice"),"orders",orders("squadron","squadron")),state.version()));assertEquals(before,RulesStateCodec.encode(state));
        state=plan(runtime,plan(runtime,state,"alice","concentrateFire"),"bob","repair");assertEquals(orders("navigate","concentrateFire"),state.entities().get(id("alice")).attributes().get("orders"));
        state=reveal(runtime,runtime.restore(RulesStateCodec.encode(state)),"alice");assertNotNull(state.control().decision());assertEquals(RuleValue.text("navigate"),state.entities().get(id("alice")).attributes().get("revealedCommand"));
    }
    @Test void humansAndProjectionOnlyCpuCompleteTheCourseWithRestoreAtEveryChoice() throws Exception {
        var runtime=runtime(source());var state=deploy(runtime);var cpu=new RulesCpu();int decisions=0;
        for(int n=0;n<160&&state.control().status()!=RulesState.Status.FINISHED;n++) {
            state=runtime.restore(RulesStateCodec.encode(state));RulesCpu.Command selected=null;
            for(String actor:state.players()) { var command=cpu.choose(runtime.decisionInput(state,actor));if(command.isPresent()){selected=command.get();break;} }
            assertNotNull(selected,"No command at "+state.control());if(selected.type().equals("respond"))decisions++;
            state=SpatialConformanceTest.apply(runtime,state,selected);assertEquals(0,state.control().mechanics().random().position());
        }
        assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals(12,decisions);assertEquals(12,state.events().stream().filter(e->e.type().equals("rules.maneuvered")).count());
    }
    @Test void optionalSpeedControlsAreTypedBoundedAndRemainAbsentForOldDefinitions() throws Exception {
        var old=runtime(ManeuverConformanceTest.source());var oldHint=old.candidates(ManeuverConformanceTest.active(old),"alice").getFirst().maneuver();assertNull(oldHint.speeds());assertNull(oldHint.note());
        var invalid=source();((ObjectNode)invalid.at("/actions/maneuver/maneuver")).put("speeds",1);assertEquals("TYPE_MISMATCH",new RulesCompiler().compile(invalid.toString()).diagnostics().getFirst().code());
        for(String values:List.of("[2,2]","[5]","[-1]")) { var source=source();((ObjectNode)source.at("/actions/maneuver/maneuver")).set("speeds",JSON.readTree("{\"list\":{\"elementType\":\"integer\",\"items\":"+values+"}}"));var runtime=runtime(source);assertTrue(runtime.candidates(ready(runtime,"keepDial"),"alice").isEmpty()); }
    }
}
