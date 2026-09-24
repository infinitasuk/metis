package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.math.BigDecimal;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.*;
import static com.metis.engine.core.rules.runtime.SpatialGeometry.*;

/** The same planner is used by suggestions, preview and committed movement. */
final class SpatialMachine {
    private final RuntimeMachine machine;
    SpatialMachine(RuntimeMachine machine) { this.machine=machine; }
    public record Pose(double x,double y,double heading) { }
    public record Plan(String entity,String board,String unit,Pose from,Pose to,List<Point> path,double distance) { public Plan { path=List.copyOf(path); } }
    private SpatialRules rules() { return machine.rules.space(); }
    private void tick() { machine.budget.spend("/space"); }
    private EvaluationContext.Entity entity(RuleValue value) { machine.checked(value,RuleType.ENTITY,"/space/entity",true);return machine.entities.get(((RuleValue.EntityRef)value.value()).id()); }
    private SpatialRules.Body binding(EvaluationContext.Entity entity) { var body=rules().bodies().get(entity.type());require(body!=null,"SPATIAL_BODY","/space","Entity has no spatial binding");return body; }
    private static String text(Map<String,RuleValue> args,String key) { return (String)args.get(key).value(); }
    private static double number(RuleValue value) { return value.number().doubleValue(); }
    private static boolean flag(EvaluationContext.Entity entity,String field) { var value=entity.attributes().get(field);require(value!=null,"SPATIAL_FIELD","/space","Spatial field is unavailable");return value.bool(); }
    private static double coordinate(EvaluationContext.Entity entity,String field) { var value=entity.attributes().get(field);require(value!=null,"SPATIAL_FIELD","/space","Spatial field is unavailable");double n=number(value);require(Double.isFinite(n),"POSITION_INVALID","/space","Coordinates must be finite");return n; }
    private static Point position(EvaluationContext.Entity e,SpatialRules.Body body) { return new Point(coordinate(e,body.x()),coordinate(e,body.y())); }
    private Footprint footprint(EvaluationContext.Entity e,SpatialRules.Body body) { return SpatialGeometry.footprint(body,rules().boards().get(body.board()),position(e,body),coordinate(e,body.facing())); }
    private List<EvaluationContext.Entity> obstacles(EvaluationContext.Entity mover,boolean opaque) {
        var body=binding(mover);return machine.entities.values().stream().filter(e->!e.id().equals(mover.id())&&rules().bodies().containsKey(e.type()))
                .filter(e->{var b=binding(e);return b.board().equals(body.board())&&flag(e,b.placed())&&flag(e,opaque?b.opaque():b.solid());}).toList();
    }
    Map<String,EvaluationContext.QueryHandler> queries() {
        var result=new TreeMap<String,EvaluationContext.QueryHandler>();
        for(String name:List.of("space.canMove","space.distance","space.lineOfSight","space.inArc","space.inZone","space.paths"))result.put(name,(args,ignored)->query(name,args));return result;
    }
    RuleValue execute(String op,Map<String,RuleValue> args) {
        tick();
        if(op.equals("space.move")) {
            var entity=entity(args.get("entity"));var plan=plan(entity,text(args,"policy"),points(args.get("path")),number(args.get("heading")));
            place(entity,plan.to(),true);machine.movements.add(plan);machine.event("rules.spaceMoved",CompiledRules.Audience.PUBLIC,null,
                    Map.of("entity",RuleValue.entity(entity.type(),entity.id()),"x",decimal(plan.to().x()),"y",decimal(plan.to().y()),"heading",decimal(plan.to().heading()),"distance",decimal(plan.distance())),"/space");return null;
        }
        if(op.equals("space.deploy")) {
            var entity=entity(args.get("entity"));var body=binding(entity);require(!flag(entity,body.placed()),"ALREADY_DEPLOYED","/space","Entity is already placed");
            var zone=zone(text(args,"zone"));require(zone.board().equals(body.board()),"BOARD_MISMATCH","/space","Deployment zone is on another board");
            require(zone.ownerSeat()<0||zone.ownerSeat()<machine.before.players().size()&&Objects.equals(entity.owner(),machine.before.players().get(zone.ownerSeat())),"DEPLOYMENT_OWNER","/space","Entity cannot use that deployment zone");
            var point=new Point(number(args.get("x")),number(args.get("y")));double heading=number(args.get("heading"));var board=rules().boards().get(body.board());validatePosition(body,board,point,heading);
            var shape=SpatialGeometry.footprint(body,board,point,heading);require(inside(shape,zone.x(),zone.y(),zone.width(),zone.height(),board.tolerance()),"DEPLOYMENT_ZONE","/space","Footprint is outside its zone");
            collision(entity,shape,shape.center(),board,true);place(entity,new Pose(point.x(),point.y(),SpatialGeometry.heading(heading)),true);machine.system("entityDeployed","/space");return null;
        }
        return query(op,args);
    }
    Plan plan(EvaluationContext.Entity entity,String policy,List<Point> path,double facing) {
        tick();var body=binding(entity);var board=rules().boards().get(body.board());var movement=rules().movements().get(policy);
        require(movement!=null,"MOVEMENT_POLICY","/space","Unknown movement policy");require(flag(entity,body.placed()),"NOT_DEPLOYED","/space","Place the entity before moving");
        require(!movement.ownerOnly()||Objects.equals(entity.owner(),machine.actor),"MOVEMENT_OWNER","/space","Actor does not control this movement subject");
        require(!path.isEmpty()&&path.size()<=machine.rules.limits().maxCollectionSize(),"PATH_INVALID","/space","A bounded nonempty path is required");
        Point origin=position(entity,body),previous=origin;double originalFacing=coordinate(entity,body.facing()),distance=0;
        require(Double.isFinite(facing)&&turn(originalFacing,facing)<=movement.maximumTurn()+board.tolerance(),"HEADING_INVALID","/space","Heading change exceeds policy");
        double allowance=coordinate(entity,movement.allowance());require(allowance>=0,"MOVEMENT_ALLOWANCE","/space","Negative movement allowance");
        for(var point:path) {
            tick();validatePosition(body,board,point,originalFacing);double step=measure(previous,point,board.metric());distance+=step;
            if(board.model()==SpatialRules.Model.GRID) {
                double dx=Math.abs(point.x()-previous.x()),dy=Math.abs(point.y()-previous.y());
                require(dx<=1&&dy<=1&&(board.metric()!=SpatialRules.Metric.MANHATTAN||dx+dy<=1),"GRID_PATH","/space","Grid paths must enumerate adjacent cells");
                if(movement.collisions()&&movement.traversal()==SpatialRules.Traversal.SWEPT&&!board.cornerCutting()&&dx==1&&dy==1) {
                    require(!occupied(entity,new Point(previous.x(),point.y()))&&!occupied(entity,new Point(point.x(),previous.y())),"CORNER_BLOCKED","/space","Diagonal corner cutting is forbidden");
                }
            }
            if(movement.collisions()&&movement.traversal()==SpatialRules.Traversal.SWEPT) {
                if(board.model()==SpatialRules.Model.GRID)require(!occupied(entity,point),"MOVEMENT_COLLISION","/space","Path crosses an occupied cell");
                else collision(entity,SpatialGeometry.footprint(body,board,previous,originalFacing),point,board,false);
            }
            previous=point;
        }
        require(distance<=allowance+board.tolerance(),"MOVEMENT_DISTANCE","/space","Path exceeds movement allowance");
        validatePosition(body,board,previous,facing);var destination=SpatialGeometry.footprint(body,board,previous,facing);
        if(movement.collisions())collision(entity,destination,destination.center(),board,true);
        if(board.model()==SpatialRules.Model.CONTINUOUS&&body.shape()==SpatialRules.Shape.RECTANGLE&&turn(originalFacing,facing)>board.tolerance()) {
            var clearance=new Footprint(previous,StrictMath.hypot(body.width(),body.height())/2,List.of());
            require(inside(clearance,0,0,board.width(),board.height(),board.tolerance()),"ROTATION_BOUNDS","/space","Rotation clearance crosses board edge");
            if(movement.collisions())collision(entity,clearance,previous,board,false);
        }
        return new Plan(entity.id(),body.board(),board.unit(),new Pose(origin.x(),origin.y(),heading(originalFacing)),new Pose(previous.x(),previous.y(),heading(facing)),path,distance);
    }
    private void collision(EvaluationContext.Entity entity,Footprint start,Point destination,SpatialRules.Board board,boolean endOnly) {
        for(var other:obstacles(entity,false)) {
            tick();var shape=footprint(other,binding(other));
            boolean hit=board.model()==SpatialRules.Model.GRID?distance(start.center(),shape.center())<board.tolerance():SpatialGeometry.swept(start,destination,shape,board.touchingAllowed(),board.tolerance());
            require(!hit,"MOVEMENT_COLLISION","/space",endOnly?"Destination is occupied":"Path crosses a solid footprint");
        }
    }
    private boolean occupied(EvaluationContext.Entity entity,Point point) { return obstacles(entity,false).stream().anyMatch(other->position(other,binding(other)).equals(point)); }
    static void validatePosition(SpatialRules.Body body,SpatialRules.Board board,Point point,double facing) {
        require(Double.isFinite(point.x())&&Double.isFinite(point.y())&&Double.isFinite(facing),"POSITION_INVALID","/space","Pose must be finite");
        if(board.model()==SpatialRules.Model.GRID)require(point.x()==Math.rint(point.x())&&point.y()==Math.rint(point.y()),"GRID_POSITION","/space","Grid coordinates must be integral");
        require(inside(SpatialGeometry.footprint(body,board,point,facing),0,0,board.width(),board.height(),board.tolerance()),"BOARD_BOUNDS","/space","Footprint must remain on the board");
    }
    private void place(EvaluationContext.Entity entity,Pose pose,boolean placed) {
        var body=binding(entity);var schema=machine.rules.entities().get(entity.type());var values=new TreeMap<>(entity.attributes());
        for(var entry:Map.of(body.x(),pose.x(),body.y(),pose.y(),body.facing(),pose.heading()).entrySet()) {
            var type=schema.attributes().get(entry.getKey()).type();RuleValue value=type.equals(RuleType.INTEGER)?RuleValue.integer(BigDecimal.valueOf(entry.getValue()).longValueExact()):decimal(entry.getValue());values.put(entry.getKey(),value);
        }
        values.put(body.placed(),RuleValue.bool(placed));machine.entities.put(entity.id(),new EvaluationContext.Entity(entity.type(),entity.id(),entity.owner(),values,entity.resources()));
    }
    private SpatialRules.Zone zone(String id) { var zone=rules().zones().get(id);require(zone!=null,"ZONE_UNKNOWN","/space","Unknown zone");return zone; }
    private static RuleValue decimal(double value) { require(Double.isFinite(value),"POSITION_INVALID","/space","Non-finite geometry result");return RuleValue.decimal(BigDecimal.valueOf(value)); }
    private static List<Point> points(RuleValue value) {
        var points=new ArrayList<Point>();for(var point:value.items()) { require(point.items().size()==2,"PATH_INVALID","/space","Path points require x and y");points.add(new Point(number(point.items().get(0)),number(point.items().get(1)))); }return points;
    }
    private static RuleValue path(List<Point> points) { return RuleValue.list(RuleType.list(RuleType.DECIMAL),points.stream().map(p->RuleValue.list(RuleType.DECIMAL,List.of(decimal(p.x()),decimal(p.y())))).toList()); }
    RuleValue query(String op,Map<String,RuleValue> args) {
        tick();
        if(op.equals("space.canMove")) { try { plan(entity(args.get("entity")),text(args,"policy"),points(args.get("path")),number(args.get("heading")));return RuleValue.bool(true); }catch(RuleEvaluationException rejected){bounded(rejected);return RuleValue.bool(false);} }
        if(op.equals("space.inZone")) {
            var entity=entity(args.get("entity"));var body=binding(entity);var zone=zone(text(args,"zone"));
            return RuleValue.bool(flag(entity,body.placed())&&zone.board().equals(body.board())&&inside(footprint(entity,body),zone.x(),zone.y(),zone.width(),zone.height(),rules().boards().get(body.board()).tolerance()));
        }
        if(op.equals("space.paths"))return paths(entity(args.get("entity")),text(args,"policy"),args.get("targets").items().stream().map(this::entity).toList());
        var from=entity(args.get("from"));var to=entity(args.get("to"));var a=binding(from);var b=binding(to);
        require(a.board().equals(b.board())&&flag(from,a.placed())&&flag(to,b.placed()),"BOARD_MISMATCH","/space","Targets must be placed on the same board");var board=rules().boards().get(a.board());
        if(op.equals("space.distance")) {
            String mode=text(args,"mode");require(Set.of("CENTRE","EDGES","BOARD").contains(mode),"DISTANCE_MODE","/space","Unknown distance measure");
            return decimal(mode.equals("EDGES")?edgeDistance(footprint(from,a),footprint(to,b)):measure(position(from,a),position(to,b),mode.equals("BOARD")?board.metric():SpatialRules.Metric.EUCLIDEAN));
        }
        if(op.equals("space.inArc")) {
            double start=number(args.get("start")),sweep=number(args.get("sweep"));require(Double.isFinite(start)&&sweep>=0&&sweep<=360,"ARC_INVALID","/space","Invalid angular sector");
            var delta=position(to,b).minus(position(from,a));double angle=StrictMath.toDegrees(StrictMath.atan2(delta.y(),delta.x()));
            return RuleValue.bool(heading(angle-coordinate(from,a.facing())-start)<=sweep+board.tolerance());
        }
        require(op.equals("space.lineOfSight"),"OPERATION_UNAVAILABLE","/space","Unknown spatial query");
        for(var obstacle:obstacles(from,true)) { tick();if(!obstacle.id().equals(to.id())&&obstructed(footprint(from,a).center(),footprint(to,b).center(),footprint(obstacle,binding(obstacle)),board.touchingAllowed(),board.tolerance()))return RuleValue.bool(false); }
        return RuleValue.bool(true);
    }
    private RuleValue paths(EvaluationContext.Entity entity,String policy,List<EvaluationContext.Entity> targets) {
        var body=binding(entity);var board=rules().boards().get(body.board());var move=rules().movements().get(policy);require(move!=null,"MOVEMENT_POLICY","/space","Unknown movement policy");
        var origin=position(entity,body);double heading=coordinate(entity,body.facing()),allowance=coordinate(entity,move.allowance());var candidates=new ArrayList<List<Point>>();
        if(board.model()==SpatialRules.Model.GRID) {
            record Route(Point end,List<Point> path,double cost) { }
            var open=new PriorityQueue<Route>(Comparator.comparingDouble(Route::cost).thenComparingDouble(r->r.end().x()).thenComparingDouble(r->r.end().y()));var best=new HashMap<Point,Double>();
            open.add(new Route(origin,List.of(),0));best.put(origin,0.0);
            while(!open.isEmpty()&&best.size()<=machine.rules.limits().maxCollectionSize()) {
                tick();var current=open.remove();if(current.cost()>best.get(current.end())+board.tolerance())continue;
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++) {
                    if(dx==0&&dy==0||board.metric()==SpatialRules.Metric.MANHATTAN&&dx!=0&&dy!=0)continue;
                    var next=new Point(current.end().x()+dx,current.end().y()+dy);double cost=current.cost()+measure(current.end(),next,board.metric());
                    if(cost>allowance+board.tolerance()||cost>=best.getOrDefault(next,Double.POSITIVE_INFINITY)-board.tolerance())continue;
                    var route=new ArrayList<>(current.path());route.add(next);
                    try { plan(entity,policy,route,heading); }catch(RuleEvaluationException invalid){bounded(invalid);continue;}
                    best.put(next,cost);open.add(new Route(next,List.copyOf(route),cost));candidates.add(List.copyOf(route));
                }
            }
        } else {
            var directions=new ArrayList<Double>();for(var target:targets)if(!target.id().equals(entity.id())&&binding(target).board().equals(body.board())) { var p=position(target,binding(target));directions.add(StrictMath.atan2(p.y()-origin.y(),p.x()-origin.x())); }
            for(int i=0;i<8;i++)directions.add(i*StrictMath.PI/4);
            for(double angle:directions)for(double fraction:new double[]{1,.5,.25}) {
                var route=List.of(new Point(origin.x()+StrictMath.cos(angle)*allowance*fraction,origin.y()+StrictMath.sin(angle)*allowance*fraction));
                try { plan(entity,policy,route,heading);candidates.add(route); }catch(RuleEvaluationException invalid){bounded(invalid);}
            }
        }
        var destinations=targets.stream().filter(t->!t.id().equals(entity.id())&&binding(t).board().equals(body.board())).map(t->position(t,binding(t))).toList();
        candidates.sort(Comparator.<List<Point>>comparingDouble(route->destinations.stream().mapToDouble(target->distance(route.getLast(),target)).min().orElse(distance(origin,route.getLast()))));
        return RuleValue.list(RuleType.list(RuleType.list(RuleType.DECIMAL)),candidates.stream().distinct().limit(machine.rules.limits().maxCollectionSize()).map(SpatialMachine::path).toList());
    }
    private static void bounded(RuleEvaluationException error) { if(error.code().equals("EVALUATION_LIMIT"))throw error; }
}
