package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.SpatialConformanceTest.*;
import static com.metis.engine.core.rules.runtime.SpatialBoundaryTest.attribute;
import static org.junit.jupiter.api.Assertions.*;

class ManeuverConformanceTest {
    static ObjectNode source() throws Exception { return (ObjectNode)read("/rules/catalogue/armada-ship-maneuvers.json"); }
    static RulesState deployed(RulesRuntime runtime) {
        var state=start(runtime);var cpu=new RulesCpu();
        for(int n=0;state.control().flow().phase().equals("deployment");n++) {
            assertTrue(n<8);String actor=state.players().get(state.control().flow().playerIndex());
            state=apply(runtime,state,cpu.choose(runtime.decisionInput(state,actor)).orElseThrow());
        }
        return state;
    }
    static RulesState active(RulesRuntime runtime) { var state=deployed(runtime);return accepted(runtime.activate(state,"alice","seat0-ship0",state.version())); }
    static Map<String,RuleValue> move(int speed,String side,int... yaw) {
        return Map.of("entity",RuleValue.entity("ship","seat0-ship0"),"speed",RuleValue.integer(speed),"side",RuleValue.text(side),
                "yaw",RuleValue.list(RuleType.INTEGER,Arrays.stream(yaw).mapToObj(RuleValue::integer).toList()));
    }
    static void sameRejection(RulesRuntime runtime,RulesState state,Map<String,RuleValue> parameters,String code) {
        String before=RulesStateCodec.encode(state);assertEquals(code,runtime.preview(state,"alice","maneuver",parameters).code());
        rejected(code,runtime.act(state,"alice","maneuver",parameters,state.version()));assertEquals(before,RulesStateCodec.encode(state));
    }
    @Test void attachmentGeometryAgreesWithHandConstructedRightAngles() {
        var origin=new SpatialGeometry.Point(10,10);
        var port=ManeuverGeometry.plan(origin,0,List.of(1),"PORT",3,90,0,2,0);
        assertEquals(new SpatialGeometry.Point(11,8),port.destination());assertEquals(90,port.heading());
        var starboard=ManeuverGeometry.plan(origin,0,List.of(1,-1),"STARBOARD",3,90,1,2,0);
        assertEquals(List.of(new SpatialGeometry.Point(11,12),new SpatialGeometry.Point(14,12),new SpatialGeometry.Point(14,15)),starboard.trace().tool());
        assertEquals(List.of(new SpatialGeometry.Point(16,11),new SpatialGeometry.Point(13,13)),starboard.path());assertEquals(0,starboard.heading());
        var straight=ManeuverGeometry.plan(origin,0,List.of(0,0),"PORT",2.6265,22.5,.618,1.127,.64);
        assertEquals(15.253,straight.destination().x(),1e-12);assertEquals(10,straight.destination().y(),1e-12);
        var zero=ManeuverGeometry.plan(origin,-360,List.of(),"STARBOARD",3,90,1,2,.64);
        assertEquals(origin,zero.destination());assertEquals(0,zero.distance());assertTrue(zero.path().isEmpty());assertEquals(1,zero.trace().tool().size());
    }
    @Test void headingAndPortStarboardMirroringAreRigidBodyInvariants() {
        for(int speed=1;speed<=4;speed++) {
            var yaw=Collections.nCopies(speed,1);var inverse=Collections.nCopies(speed,-1);var origin=new SpatialGeometry.Point(0,0);
            var a=ManeuverGeometry.plan(origin,0,yaw,"PORT",2.6265,22.5,.618,1.127,.64);
            var b=ManeuverGeometry.plan(origin,0,inverse,"STARBOARD",2.6265,22.5,.618,1.127,.64);
            assertEquals(a.destination().x(),b.destination().x(),1e-12);assertEquals(-a.destination().y(),b.destination().y(),1e-12);
            var rotated=ManeuverGeometry.plan(new SpatialGeometry.Point(20,30),90,yaw,"PORT",2.6265,22.5,.618,1.127,.64);
            assertEquals(20-a.destination().y(),rotated.destination().x(),1e-12);assertEquals(30+a.destination().x(),rotated.destination().y(),1e-12);
            assertEquals(a.distance(),rotated.distance(),1e-12);assertEquals(speed*22.5,a.heading());
        }
    }
    @Test void sourcedChartsDriveControlsAndPreviewCommitRestoreReplayAgree() throws Exception {
        var runtime=runtime(source());var state=active(runtime);String original=RulesStateCodec.encode(state);
        var control=runtime.candidates(state,"alice").getFirst().maneuver();assertNotNull(control);assertEquals(List.of(0,1,1,2),control.chart().get(4));
        var offers=runtime.offeredActions(state,"alice");assertFalse(offers.isEmpty());assertTrue(offers.size()<=64);
        for(var offer:offers) {
            var preview=runtime.preview(state,"alice",offer.action(),offer.parameters());assertTrue(preview.allowed(),preview.code());var movement=preview.movements().getFirst();
            assertEquals(offer.parameters().get("speed").integer(),movement.maneuver().speed());assertEquals(movement.maneuver().speed()+1,movement.maneuver().tool().size());
            var result=runtime.act(state,"alice",offer.action(),offer.parameters(),state.version());var next=accepted(result);var attrs=next.entities().get(movement.entity()).attributes();
            assertEquals(movement.to().x(),attrs.get("x").number().doubleValue());assertEquals(movement.to().y(),attrs.get("y").number().doubleValue());assertEquals(movement.to().heading(),attrs.get("heading").number().doubleValue());
            assertEquals(next,runtime.restore(RulesStateCodec.encode(next)));assertEquals(next,RulesRuntime.replay(state,RulesStateCodec.decodeCommit(RulesStateCodec.encode(result.commit()))));
            assertEquals(1,next.events().stream().filter(e->e.type().equals("rules.maneuvered")).count());assertEquals(original,RulesStateCodec.encode(state));
        }
        for(int speed=0;speed<=4;speed++)for(String side:List.of("PORT","STARBOARD")) {
            var p=move(speed,side,new int[speed]);var preview=runtime.preview(state,"alice","maneuver",p);assertTrue(preview.allowed(),preview.message());
            assertEquals(12+speed*2.6265,preview.movements().getFirst().to().x(),1e-12);
        }
    }
    @Test void malformedPlansAndWrongSubjectsFailAtomicallyWithIdenticalPreviewReasons() throws Exception {
        var runtime=runtime(source());var state=active(runtime);
        sameRejection(runtime,state,move(5,"PORT",0,0,0,0,0),"MANEUVER_SPEED");sameRejection(runtime,state,move(-1,"PORT"),"MANEUVER_SPEED");
        sameRejection(runtime,state,move(2,"PORT",0),"MANEUVER_JOINTS");sameRejection(runtime,state,move(0,"PORT",0),"MANEUVER_JOINTS");
        sameRejection(runtime,state,move(3,"PORT",1,0,0),"MANEUVER_YAW");sameRejection(runtime,state,move(1,"PORT",-3),"MANEUVER_YAW");
        sameRejection(runtime,state,move(1,"left",0),"MANEUVER_SIDE");
        var wrong=new TreeMap<>(move(1,"PORT",0));wrong.put("entity",RuleValue.entity("ship","seat1-ship0"));sameRejection(runtime,state,wrong,"ENTITY_INELIGIBLE");
        var committed=accepted(runtime.act(state,"alice","maneuver",move(0,"PORT"),state.version()));assertEquals(12,committed.entities().get("seat0-ship0").attributes().get("x").number().doubleValue());
        rejected("STALE_STATE",runtime.act(committed,"alice","maneuver",move(0,"PORT"),state.version()));
    }
    @Test void rotatedBoundsBaseCollisionsAndToolOverlapAreAuthoritative() throws Exception {
        var source=source();((ObjectNode)source.at("/state/startX")).set("initial",JSON.readTree("[69,60]"));((ObjectNode)source.at("/space/zones/west")).put("width",72);
        var runtime=runtime(source);sameRejection(runtime,active(runtime),move(1,"PORT",0),"BOARD_BOUNDS");
        source=source();((ObjectNode)source.at("/state/startX")).set("initial",JSON.readTree("[12,17.253]"));((ObjectNode)source.at("/state/startY")).set("initial",JSON.readTree("[12,12]"));
        ((ObjectNode)source.at("/space/zones/east")).put("x",0).put("width",72);runtime=runtime(source);sameRejection(runtime,active(runtime),move(2,"PORT",0,0),"MOVEMENT_COLLISION");
        source=source();((ObjectNode)source.at("/state/anchor")).set("initial",JSON.readTree("[0,0]"));runtime=runtime(source);sameRejection(runtime,active(runtime),move(1,"PORT",0),"MANEUVER_TOOL_OVERLAP");
    }
    @Test void validationIsBoundedAndQueriesDoNotHideBudgetExhaustion() throws Exception {
        var runtime=runtime(source());var m=new RuntimeMachine(runtime,active(runtime),false);m.actor="alice";
        var args=new TreeMap<>(move(1,"PORT",0));args.put("chart",m.entities.get("seat0-ship0").attributes().get("chart"));args.put("segment",decimal(2.6265));args.put("angle",decimal(22.5));args.put("anchor",RuleValue.list(RuleType.DECIMAL,List.of(decimal(.618),decimal(1.127))));args.put("toolWidth",decimal(.64));args.put("collisions",RuleValue.bool(true));
        assertTrue(m.maneuver.execute("maneuver.canMove",args).bool());
        for(String field:List.of("segment","angle","toolWidth")) {
            var invalid=new TreeMap<>(args);invalid.put(field,decimal(-1));assertFalse(m.maneuver.execute("maneuver.canMove",invalid).bool());
            assertEquals("MANEUVER_CALIBRATION",assertThrows(RuleEvaluationException.class,()->m.maneuver.execute("maneuver.move",invalid)).code());
        }
        var badChart=new TreeMap<>(args);badChart.put("chart",path(0,0));assertEquals("MANEUVER_CHART",assertThrows(RuleEvaluationException.class,()->m.maneuver.execute("maneuver.move",badChart)).code());
        m.actor="bob";assertFalse(m.maneuver.execute("maneuver.canMove",args).bool());m.actor="alice";
        attribute(m,"seat0-ship0","placed",RuleValue.bool(false));assertFalse(m.maneuver.execute("maneuver.canMove",args).bool());
        while(m.budget.remaining()>0)m.budget.spend("test");assertEquals("EVALUATION_LIMIT",assertThrows(RuleEvaluationException.class,()->m.maneuver.execute("maneuver.canMove",args)).code());
    }
    @Test void compilerChecksHintBindingsAndHiddenChartsFailClosed() throws Exception {
        var bad=source();((ObjectNode)bad.at("/actions/maneuver/maneuver")).put("yaw","speed");assertEquals("MANEUVER_CONTROL",new RulesCompiler().compile(bad.toString()).diagnostics().getFirst().code());
        bad=source();((ObjectNode)bad.get("modules")).remove("space");assertEquals("MODULE_REQUIRED",new RulesCompiler().compile(bad.toString()).diagnostics().getFirst().code());
        var hidden=source();((ObjectNode)hidden.at("/entities/ship/attributes/chart")).put("visibility","SERVER");var runtime=runtime(hidden);var state=active(runtime);
        assertTrue(runtime.candidates(state,"alice").isEmpty());assertTrue(runtime.offeredActions(state,"alice").isEmpty());assertFalse(runtime.preview(state,"alice","maneuver",move(1,"PORT",0)).allowed());
    }
    @Test void sharedCpuCompletesCourseAcrossRepeatedRestores() throws Exception {
        var runtime=runtime(source());var state=start(runtime);var cpu=new RulesCpu();
        for(int n=0;n<100&&state.control().status()!=RulesState.Status.FINISHED;n++) {
            runtime=runtime(source());state=runtime.restore(RulesStateCodec.encode(state));String actor=state.players().get(state.control().flow().playerIndex());
            state=apply(runtime,state,cpu.choose(runtime.decisionInput(state,actor)).orElseThrow());
        }
        assertEquals(RulesState.Status.FINISHED,state.control().status());assertEquals("maneuver-training-complete",state.control().outcome());
        assertEquals(12,state.events().stream().filter(e->e.type().equals("rules.maneuvered")).count());assertTrue(state.control().winners().isEmpty());
    }
}
