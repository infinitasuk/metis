package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.SpatialConformanceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialBoundaryTest {
    static RuntimeMachine machine(String game) throws Exception { var runtime=runtime(example(game));var m=new RuntimeMachine(runtime,start(runtime),false);m.actor="alice";return m; }
    static void attribute(RuntimeMachine m,String id,String name,RuleValue value) { var e=m.entities.get(id);var attrs=new TreeMap<>(e.attributes());attrs.put(name,value);m.entities.put(id,new EvaluationContext.Entity(e.type(),id,e.owner(),attrs,e.resources())); }
    static void bad(String code,Runnable action) { assertEquals(code,assertThrows(RuleEvaluationException.class,action::run).code()); }
    static Map<String,RuleValue> queryArgs(String type,String from,String to) { return Map.of("from",RuleValue.entity(type,from),"to",RuleValue.entity(type,to)); }
    static Map<String,RuleValue> moving(String type,String id,String policy,double heading,double... points) { var values=new TreeMap<>(move(type,id,heading,points));values.put("policy",RuleValue.text(policy));return values; }
    @Test void continuousQueriesRespectDistanceArcsObstructionAndDeployment() throws Exception {
        var m=machine("vector-orders");var args=new TreeMap<>(queryArgs("ship","alice","bob"));args.put("mode",RuleValue.text("CENTRE"));assertEquals(8,m.spatial.query("space.distance",args).number().doubleValue());
        args.put("mode",RuleValue.text("EDGES"));assertEquals(7,m.spatial.query("space.distance",args).number().doubleValue());args.put("mode",RuleValue.text("invalid"));bad("DISTANCE_MODE",()->m.spatial.query("space.distance",args));
        args.put("start",decimal(-45));args.put("sweep",decimal(90));assertTrue(m.spatial.query("space.inArc",args).bool());args.put("start",decimal(45));assertFalse(m.spatial.query("space.inArc",args).bool());args.put("sweep",decimal(361));bad("ARC_INVALID",()->m.spatial.query("space.inArc",args));
        assertTrue(m.spatial.query("space.lineOfSight",args).bool());var e=m.entities.get("alice");m.entities.put("terrain",new EvaluationContext.Entity("ship","terrain",null,e.attributes(),e.resources()));attribute(m,"terrain","x",decimal(6));attribute(m,"terrain","opaque",RuleValue.bool(true));assertFalse(m.spatial.query("space.lineOfSight",args).bool());attribute(m,"terrain","y",decimal(7));assertTrue(m.spatial.query("space.lineOfSight",args).bool());attribute(m,"terrain","placed",RuleValue.bool(false));assertTrue(m.spatial.query("space.lineOfSight",args).bool());
        attribute(m,"bob","placed",RuleValue.bool(false));bad("BOARD_MISMATCH",()->m.spatial.query("space.distance",args));
        var zone=Map.of("entity",RuleValue.entity("ship","bob"),"zone",RuleValue.text("east"));assertFalse(m.spatial.query("space.inZone",zone).bool());
        var deploy=new TreeMap<>(zone);deploy.put("x",decimal(10));deploy.put("y",decimal(5));deploy.put("heading",decimal(360));m.spatial.execute("space.deploy",deploy);assertTrue(m.spatial.query("space.inZone",zone).bool());bad("ALREADY_DEPLOYED",()->m.spatial.execute("space.deploy",deploy));
        attribute(m,"bob","placed",RuleValue.bool(false));deploy.put("zone",RuleValue.text("west"));bad("DEPLOYMENT_OWNER",()->m.spatial.execute("space.deploy",deploy));deploy.put("zone",RuleValue.text("missing"));bad("ZONE_UNKNOWN",()->m.spatial.execute("space.deploy",deploy));deploy.put("zone",RuleValue.text("east"));deploy.put("x",decimal(6));bad("DEPLOYMENT_ZONE",()->m.spatial.execute("space.deploy",deploy));
    }
    @Test void plannerCannotSkipThinObstaclesOrOverdrawMovementAllowance() throws Exception {
        var m=machine("vector-orders");var move=moving("ship","alice","sail",0,5,5);assertTrue(m.spatial.query("space.canMove",move).bool());
        move.put("path",path(6,5));assertFalse(m.spatial.query("space.canMove",move).bool());bad("MOVEMENT_DISTANCE",()->m.spatial.execute("space.move",move));move.put("policy",RuleValue.text("missing"));bad("MOVEMENT_POLICY",()->m.spatial.execute("space.move",move));
        move.put("policy",RuleValue.text("sail"));move.put("path",path(5,5));m.actor="bob";bad("MOVEMENT_OWNER",()->m.spatial.execute("space.move",move));m.actor="alice";attribute(m,"alice","placed",RuleValue.bool(false));bad("NOT_DEPLOYED",()->m.spatial.execute("space.move",move));attribute(m,"alice","placed",RuleValue.bool(true));attribute(m,"alice","speed",decimal(-1));bad("MOVEMENT_ALLOWANCE",()->m.spatial.execute("space.move",move));
        attribute(m,"alice","speed",decimal(20));attribute(m,"bob","x",decimal(4));bad("MOVEMENT_COLLISION",()->m.spatial.execute("space.move",move));attribute(m,"bob","alive",RuleValue.bool(false));m.spatial.execute("space.move",move);assertEquals(5,m.entities.get("alice").attributes().get("x").number().doubleValue());
        move.put("path",RuleValue.list(RuleType.list(RuleType.DECIMAL),List.of(RuleValue.list(RuleType.DECIMAL,List.of(decimal(1))))));bad("PATH_INVALID",()->m.spatial.execute("space.move",move));
    }
    @Test void rectangleRotationUsesConservativeClearanceAndTurnLimits() throws Exception {
        var source=example("vector-orders");((ObjectNode)source.at("/space/bodies/ship")).put("shape","RECTANGLE").put("width",2).put("height",1);((ObjectNode)source.at("/entities/ship/attributes/speed")).put("initial",20);
        var runtime=runtime(source);var m=new RuntimeMachine(runtime,start(runtime),false);m.actor="alice";
        bad("ROTATION_BOUNDS",()->m.spatial.execute("space.move",moving("ship","alice","sail",10,2,.9)));
        bad("MOVEMENT_COLLISION",()->m.spatial.execute("space.move",moving("ship","alice","sail",90,8,5)));
        m.spatial.execute("space.move",moving("ship","alice","sail",90,5,5));assertEquals(90,m.entities.get("alice").attributes().get("heading").number().doubleValue());
        ((ObjectNode)source.at("/space/movements/sail")).put("maximumTurn",30);var limited=runtime(source);var n=new RuntimeMachine(limited,start(limited),false);n.actor="alice";bad("HEADING_INVALID",()->n.spatial.execute("space.move",moving("ship","alice","sail",31,3,5)));
        n.spatial.execute("space.move",moving("ship","alice","sail",-30,3,5));assertEquals(330,n.entities.get("alice").attributes().get("heading").number().doubleValue());
    }
    @Test void gridDiagonalCornerAndDestinationOnlyPoliciesAreDefinitionOwned() throws Exception {
        var source=example("grid-skirmish");((ObjectNode)source.at("/space/boards/table")).put("metric","CHEBYSHEV");var runtime=runtime(source);var m=new RuntimeMachine(runtime,start(runtime),false);m.actor="alice";attribute(m,"alice-b","x",RuleValue.integer(2));attribute(m,"alice-b","y",RuleValue.integer(1));
        var diagonal=moving("piece","alice-a","walk",0,2,2);bad("CORNER_BLOCKED",()->m.spatial.execute("space.move",diagonal));
        ((ObjectNode)source.at("/space/boards/table")).put("cornerCutting",true);runtime=runtime(source);var free=new RuntimeMachine(runtime,start(runtime),false);free.actor="alice";attribute(free,"alice-b","x",RuleValue.integer(2));attribute(free,"alice-b","y",RuleValue.integer(1));free.spatial.execute("space.move",diagonal);
        ((ObjectNode)source.at("/space/movements/walk")).put("traversal","DESTINATION");runtime=runtime(source);var jump=new RuntimeMachine(runtime,start(runtime),false);jump.actor="alice";attribute(jump,"alice-b","x",RuleValue.integer(2));attribute(jump,"alice-b","y",RuleValue.integer(1));jump.spatial.execute("space.move",moving("piece","alice-a","walk",0,2,1,3,1));
        ((ObjectNode)source.at("/space/movements/walk")).put("collisions",false).put("ownerOnly",false);runtime=runtime(source);var ghost=new RuntimeMachine(runtime,start(runtime),false);ghost.actor="bob";ghost.spatial.execute("space.move",moving("piece","alice-a","walk",0,1,2,1,3));
    }
    @Test void malformedOrOutOfBoundsPosesCannotEnterADurableCheckpoint() throws Exception {
        var source=example("grid-skirmish");var runtime=runtime(source);var state=start(runtime);var encoded=(ObjectNode)JSON.readTree(RulesStateCodec.encode(state));((ObjectNode)encoded.at("/entities/alice-a/attributes/x")).put("value",100);
        bad("BOARD_BOUNDS",()->runtime.restore(encoded.toString()));
        var m=new RuntimeMachine(runtime,state,false);attribute(m,"alice-a","x",RuleValue.integer(-1));bad("BOARD_BOUNDS",m::commit);
        var moves=moving("piece","alice-a","walk",0,2,1);var budget=new RuntimeMachine(runtime,state,false);budget.actor="alice";while(budget.budget.remaining()>0)budget.budget.spend("test");bad("EVALUATION_LIMIT",()->budget.spatial.query("space.canMove",moves));
    }
    @Test void movementPreviewsIncludeCostsAndFailClosedOnHiddenInputs() throws Exception {
        var source=example("grid-skirmish");var steps=(com.fasterxml.jackson.databind.node.ArrayNode)source.at("/procedures/move/steps");steps.add(JSON.readTree("{\"op\":\"core.modifyResource\",\"entity\":{\"var\":\"entity\"},\"resource\":\"guard\",\"delta\":-1}"));
        var runtime=runtime(source);var state=start(runtime);state=accepted(runtime.activate(state,"alice","alice-a",state.version()));var p=move("piece","alice-a",0,2,1);assertTrue(runtime.preview(state,"alice","move",p).allowed());assertEquals(List.of(new RulesRuntime.ResourceChange("alice-a","guard",1,0)),runtime.preview(state,"alice","move",p).resources());var moved=accepted(runtime.act(state,"alice","move",p,state.version()));assertEquals(0,moved.entities().get("alice-a").resources().get("guard"));
        ((ObjectNode)source.at("/entities/piece/resources/guard")).put("initial",0);runtime=runtime(source);state=start(runtime);state=accepted(runtime.activate(state,"alice","alice-a",state.version()));assertEquals("RESOURCE_BOUNDS",runtime.preview(state,"alice","move",p).code());rejected("RESOURCE_BOUNDS",runtime.act(state,"alice","move",p,state.version()));
        ((ObjectNode)source.at("/entities/piece/resources/guard")).put("visibility","SERVER");runtime=runtime(source);state=start(runtime);state=accepted(runtime.activate(state,"alice","alice-a",state.version()));assertEquals("RESOURCE_UNAVAILABLE",runtime.preview(state,"alice","move",p).code());
    }
    @Test void previewNeverRollsRandomnessInAnAutomaticPhaseEntry() throws Exception {
        var source=example("grid-skirmish");((ObjectNode)source.get("modules")).put("dice","1");source.set("dice",JSON.readTree("{\"d\":{\"maxRerolls\":0,\"faces\":[{\"weight\":1,\"value\":1,\"symbols\":[]}]}}"));
        ((ObjectNode)source.at("/flow/phases/battle")).set("steps",JSON.readTree("[{\"id\":\"move\",\"actions\":[\"move\"],\"minimum\":0,\"maximum\":1}]"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)source.at("/procedures/score/steps")).insert(0,JSON.readTree("{\"op\":\"dice.roll\",\"args\":{\"owner\":{\"op\":\"at\",\"args\":[{\"var\":\"players\"},0]},\"visibility\":\"PUBLIC\",\"dice\":{\"list\":{\"elementType\":\"string\",\"items\":[\"d\"]}}},\"as\":\"roll\"}"));
        var runtime=runtime(source);var state=start(runtime);
        for(String id:List.of("alice-a","alice-b","bob-a")) { String actor=id.substring(0,id.indexOf('-'));state=accepted(runtime.activate(state,actor,id,state.version()));state=accepted(runtime.pass(state,actor,state.version())); }
        state=accepted(runtime.activate(state,"bob","bob-b",state.version()));String before=RulesStateCodec.encode(state);var parameters=move("piece","bob-b",0,6,3);
        assertEquals("PREVIEW_RANDOM_REQUIRED",runtime.preview(state,"bob","move",parameters).code());assertEquals(before,RulesStateCodec.encode(state));assertEquals(0,state.control().mechanics().random().position());
        assertEquals(1,accepted(runtime.act(state,"bob","move",parameters,state.version())).control().mechanics().random().position());
    }
    @Test void projectedQueriesDoNotTreatInvisibleEffectsAsKnownZero() throws Exception {
        var source=example("vector-orders");((ObjectNode)source.get("modules")).put("effects","1");source.set("effects",JSON.readTree("{\"hidden\":{\"duration\":\"PERSISTENT\",\"stacking\":\"ADD\",\"maximumStacks\":2,\"visibility\":\"OWNER\"},\"server\":{\"duration\":\"PERSISTENT\",\"stacking\":\"ADD\",\"maximumStacks\":2,\"visibility\":\"SERVER\"}}"));
        var runtime=runtime(source);var m=new RuntimeMachine(runtime,start(runtime),false,true);m.actor="alice";var args=new TreeMap<>(Map.of("entity",RuleValue.entity("ship","bob"),"key",RuleValue.text("hidden")));
        bad("QUERY_NOT_VISIBLE",()->m.mechanics.query("effects.total",args));bad("QUERY_NOT_VISIBLE",()->m.mechanics.query("effects.ids",args));args.put("entity",RuleValue.entity("ship","alice"));assertEquals(0,m.mechanics.query("effects.total",args).integer());args.put("key",RuleValue.text("server"));bad("QUERY_NOT_VISIBLE",()->m.mechanics.query("effects.total",args));
        args.put("period",RuleValue.text("ROUND"));assertFalse(m.mechanics.query("resources.used",args).bool());args.put("entity",RuleValue.entity("ship","bob"));bad("QUERY_NOT_VISIBLE",()->m.mechanics.query("resources.used",args));
    }
}
