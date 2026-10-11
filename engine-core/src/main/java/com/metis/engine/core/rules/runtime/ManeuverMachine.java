package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.math.BigDecimal;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.*;
import static com.metis.engine.core.rules.runtime.SpatialGeometry.*;

/** Bounded suggestions, preview and commits share this planner. No legacy battle types or game identities. */
final class ManeuverMachine {
    private final RuntimeMachine machine;
    ManeuverMachine(RuntimeMachine machine) { this.machine=machine; }
    Map<String,EvaluationContext.QueryHandler> queries() {
        return Map.of("maneuver.canMove",(args,ignored)->execute("maneuver.canMove",args),"maneuver.options",(args,ignored)->execute("maneuver.options",args));
    }
    private record Input(EvaluationContext.Entity entity,SpatialRules.Body body,SpatialRules.Board board,int speed,List<Integer> limits,
                         String side,double segment,double angle,double along,double across,double toolWidth,boolean collisions) { }
    private void tick() { machine.budget.spend("/maneuver"); }
    private double number(RuleValue value) { require(value!=null,"SPATIAL_FIELD","/maneuver","Spatial field is unavailable");double number=value.number().doubleValue();require(Double.isFinite(number),"MANEUVER_CALIBRATION","/maneuver","Dimensions must be finite");return number; }
    private boolean flag(EvaluationContext.Entity entity,String field) { var value=entity.attributes().get(field);require(value!=null,"SPATIAL_FIELD","/maneuver","Spatial field is unavailable");return value.bool(); }
    private Input input(Map<String,RuleValue> args) {
        tick();machine.checked(args.get("entity"),RuleType.ENTITY,"/maneuver",true);
        var entity=machine.entities.get(((RuleValue.EntityRef)args.get("entity").value()).id());
        var body=machine.rules.space().bodies().get(entity.type());require(body!=null,"SPATIAL_BODY","/maneuver","A spatial body is required");
        var board=machine.rules.space().boards().get(body.board());require(board.model()==SpatialRules.Model.CONTINUOUS,"MANEUVER_BOARD","/maneuver","Maneuvers use continuous coordinates");
        require(flag(entity,body.placed()),"NOT_DEPLOYED","/maneuver","Deploy before maneuvering");
        require(Objects.equals(entity.owner(),machine.actor),"MOVEMENT_OWNER","/maneuver","Only the owner can maneuver this entity");
        var chart=args.get("chart").items();require(!chart.isEmpty()&&chart.size()<=9,"MANEUVER_CHART","/maneuver","Chart supports speed zero through at most eight joints");
        for(int speed=0;speed<chart.size();speed++) {
            require(chart.get(speed).items().size()==speed,"MANEUVER_CHART","/maneuver","One yaw limit is required per joint");
            for(var limit:chart.get(speed).items()){tick();require(limit.integer()>=0&&limit.integer()<=8,"MANEUVER_CHART","/maneuver","Yaw limits must be between zero and eight");}
        }
        long speed=args.get("speed").integer();require(speed>=0&&speed<chart.size(),"MANEUVER_SPEED","/maneuver","Speed is not supported by this chart");
        String side=(String)args.get("side").value();require(Set.of("PORT","STARBOARD").contains(side),"MANEUVER_SIDE","/maneuver","Choose PORT or STARBOARD");
        var anchor=args.get("anchor").items();require(anchor.size()==2,"MANEUVER_CALIBRATION","/maneuver","Provide along/across attachment offsets");
        double segment=number(args.get("segment")),angle=number(args.get("angle")),along=number(anchor.get(0)),across=number(anchor.get(1)),width=number(args.get("toolWidth"));
        require(segment>0&&segment<=1e9&&angle>0&&angle<=90&&Math.abs(along)<=1e9&&across>=0&&across<=1e9&&width>=0&&width<=1e9,
                "MANEUVER_CALIBRATION","/maneuver","Invalid tool calibration");
        return new Input(entity,body,board,(int)speed,chart.get((int)speed).items().stream().map(v->(int)v.integer()).toList(),side,segment,angle,along,across,width,args.get("collisions").bool());
    }
    private SpatialMachine.Plan plan(Input input,List<RuleValue> values) {
        tick();require(values.size()==input.speed(),"MANEUVER_JOINTS","/maneuver","Provide exactly one yaw value per speed joint");
        var yaw=new ArrayList<Integer>();
        for(int i=0;i<values.size();i++) {
            long value=values.get(i).integer();int limit=input.limits().get(i);require(value>=-limit&&value<=limit,"MANEUVER_YAW","/maneuver","Yaw exceeds the chart at joint "+(i+1));yaw.add((int)value);
        }
        var entity=input.entity();var body=input.body();var board=input.board();
        double x=number(entity.attributes().get(body.x())),y=number(entity.attributes().get(body.y())),heading=number(entity.attributes().get(body.facing()));
        var geometry=ManeuverGeometry.plan(new Point(x,y),heading,yaw,input.side(),input.segment(),input.angle(),input.along(),input.across(),input.toolWidth());
        SpatialMachine.validatePosition(body,board,geometry.destination(),geometry.heading());
        var destination=footprint(body,board,geometry.destination(),geometry.heading());
        var tool=geometry.trace().tool();
        for(int i=1;i<tool.size();i++) {
            tick();require(!swept(new Footprint(tool.get(i-1),input.toolWidth()/2,List.of()),tool.get(i),destination,true,board.tolerance()),
                    "MANEUVER_TOOL_OVERLAP","/maneuver","The final base overlaps the used maneuver tool");
        }
        if(input.collisions())for(var other:machine.entities.values()) {
            tick();if(other.id().equals(entity.id()))continue;var otherBody=machine.rules.space().bodies().get(other.type());
            if(otherBody==null||!otherBody.board().equals(body.board())||!flag(other,otherBody.placed())||!flag(other,otherBody.solid()))continue;
            var shape=footprint(otherBody,board,new Point(number(other.attributes().get(otherBody.x())),number(other.attributes().get(otherBody.y()))),number(other.attributes().get(otherBody.facing())));
            require(!overlap(destination,shape,board.touchingAllowed(),board.tolerance()),"MOVEMENT_COLLISION","/maneuver","The destination overlaps another entity");
        }
        return new SpatialMachine.Plan(entity.id(),body.board(),board.unit(),new SpatialMachine.Pose(x,y,heading),
                new SpatialMachine.Pose(geometry.destination().x(),geometry.destination().y(),geometry.heading()),geometry.path(),geometry.distance(),geometry.trace());
    }
    RuleValue execute(String operation,Map<String,RuleValue> args) {
        if(operation.equals("maneuver.canMove")) {
            try {plan(input(args),args.get("yaw").items());return RuleValue.bool(true);}catch(RuleEvaluationException error){if(error.code().equals("EVALUATION_LIMIT"))throw error;return RuleValue.bool(false);}
        }
        var input=input(args);
        if(operation.equals("maneuver.options")) {
            var results=new ArrayList<RuleValue>();var examined=new int[]{0};suggest(input,0,new ArrayList<>(),results,examined);
            return RuleValue.list(RuleType.list(RuleType.INTEGER),results);
        }
        require(operation.equals("maneuver.move"),"OPERATION_UNAVAILABLE","/maneuver","Unknown maneuver operation");
        var plan=plan(input,args.get("yaw").items());machine.spatial.place(input.entity(),plan.to(),true);machine.movements.add(plan);
        machine.event("rules.maneuvered",CompiledRules.Audience.PUBLIC,null,Map.of("entity",args.get("entity"),"speed",args.get("speed"),"yaw",args.get("yaw"),"side",args.get("side"),
                "x",RuleValue.decimal(BigDecimal.valueOf(plan.to().x())),"y",RuleValue.decimal(BigDecimal.valueOf(plan.to().y())),"heading",RuleValue.decimal(BigDecimal.valueOf(plan.to().heading()))),"/maneuver");return null;
    }
    private void suggest(Input input,int index,List<RuleValue> yaw,List<RuleValue> results,int[] examined) {
        tick();if(results.size()>=Math.min(64,machine.rules.limits().maxCollectionSize())||examined[0]>=256)return;
        if(index==input.speed()) {
            examined[0]++;try {plan(input,yaw);results.add(RuleValue.list(RuleType.INTEGER,yaw));}catch(RuleEvaluationException error){if(error.code().equals("EVALUATION_LIMIT"))throw error;}return;
        }
        int limit=input.limits().get(index);
        for(int choice=0;choice<=limit*2;choice++) {
            int value=choice==0?0:choice%2==1?-(choice+1)/2:choice/2;
            yaw.add(RuleValue.integer(value));suggest(input,index+1,yaw,results,examined);yaw.removeLast();
        }
    }
}
