package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

final class SpatialCompiler {
    private SpatialCompiler() { }
    static SpatialRules compile(JsonNode root,CompileScope scope,Set<String> modules) {
        var node=root.path("space");
        if(!node.isMissingNode())scope.expect(modules.contains("space"),"MODULE_REQUIRED","/space","Spatial configuration requires space module");
        var boards=new TreeMap<String,SpatialRules.Board>();var bodies=new TreeMap<String,SpatialRules.Body>();var movements=new TreeMap<String,SpatialRules.Movement>();var zones=new TreeMap<String,SpatialRules.Zone>();
        for(String id:CompileScope.keys(node.path("boards"))) {
            var b=node.get("boards").get(id);String path="/space/boards/"+id;
            var model=SpatialRules.Model.valueOf(b.get("model").textValue());var metric=SpatialRules.Metric.valueOf(b.get("metric").textValue());
            double width=b.get("width").doubleValue(),height=b.get("height").doubleValue();
            scope.expect(model!=SpatialRules.Model.GRID||(width==Math.rint(width)&&height==Math.rint(height)),"GRID_DIMENSIONS",path,"Grid dimensions must be integral");
            scope.expect(model!=SpatialRules.Model.CONTINUOUS||metric==SpatialRules.Metric.EUCLIDEAN,"SPACE_METRIC",path,"Measured-space paths use Euclidean distance");
            boards.put(id,new SpatialRules.Board(model,width,height,b.get("unit").textValue(),metric,b.get("cornerCutting").booleanValue(),b.get("touchingAllowed").booleanValue(),b.get("tolerance").doubleValue()));
        }
        for(String id:CompileScope.keys(node.path("bodies"))) {
            var b=node.get("bodies").get(id);String path="/space/bodies/"+id;var entity=scope.entities.get(id);scope.expect(entity!=null,"ENTITY_TYPE_UNKNOWN",path,"Unknown spatial entity type");
            String board=b.get("board").textValue();scope.expect(boards.containsKey(board),"BOARD_UNKNOWN",path+"/board","Unknown board");
            scope.expect(new HashSet<>(List.of(b.get("x").textValue(),b.get("y").textValue(),b.get("facing").textValue())).size()==3,"SPATIAL_FIELD",path,"Pose bindings must be distinct");
            for(String key:List.of("x","y","facing","placed","solid","opaque")) {
                String field=b.get(key).textValue();var declaration=entity.attributes().get(field);
                scope.expect(declaration!=null&&declaration.visibility()==CompiledRules.Audience.PUBLIC,"SPATIAL_FIELD",path+"/"+key,"Spatial fields must be declared PUBLIC attributes");
                scope.expect(List.of("x","y","facing").contains(key)?declaration.type().numeric():declaration.type().equals(RuleType.BOOLEAN),"TYPE_MISMATCH",path+"/"+key,"Invalid spatial field type");
                if(boards.get(board).model()==SpatialRules.Model.GRID&&List.of("x","y").contains(key))scope.expect(declaration.type().equals(RuleType.INTEGER),"GRID_FIELD",path+"/"+key,"Grid coordinates use integer attributes");
                if(key.equals("facing")||boards.get(board).model()==SpatialRules.Model.CONTINUOUS&&List.of("x","y").contains(key))scope.expect(declaration.type().equals(RuleType.DECIMAL),"SPATIAL_FIELD",path+"/"+key,"Measured coordinates and headings use decimal attributes");
            }
            var shape=SpatialRules.Shape.valueOf(b.get("shape").textValue());double radius=b.get("radius").doubleValue(),width=b.get("width").doubleValue(),height=b.get("height").doubleValue();
            scope.expect(boards.get(board).model()!=SpatialRules.Model.GRID||shape==SpatialRules.Shape.POINT,"GRID_FOOTPRINT",path,"Grid v1 uses single-cell pieces");
            scope.expect(shape!=SpatialRules.Shape.CIRCLE||radius>0,"FOOTPRINT_INVALID",path,"Circle radius must be positive");
            scope.expect(shape!=SpatialRules.Shape.RECTANGLE||width>0&&height>0,"FOOTPRINT_INVALID",path,"Rectangle dimensions must be positive");
            bodies.put(id,new SpatialRules.Body(board,b.get("x").textValue(),b.get("y").textValue(),b.get("facing").textValue(),b.get("placed").textValue(),b.get("solid").textValue(),b.get("opaque").textValue(),shape,radius,width,height));
        }
        for(String id:CompileScope.keys(node.path("movements"))) {
            var m=node.get("movements").get(id);movements.put(id,new SpatialRules.Movement(SpatialRules.Traversal.valueOf(m.get("traversal").textValue()),m.get("allowance").textValue(),m.get("maximumTurn").doubleValue(),m.get("collisions").booleanValue(),m.get("ownerOnly").booleanValue()));
            for(var body:bodies.entrySet()) {
                var field=scope.entities.get(body.getKey()).attributes().get(m.get("allowance").textValue());
                scope.expect(field!=null&&field.type().numeric()&&field.visibility()==CompiledRules.Audience.PUBLIC,"MOVEMENT_ALLOWANCE","/space/movements/"+id,"Every spatial body requires a public numeric allowance attribute");
            }
        }
        for(String id:CompileScope.keys(node.path("zones"))) {
            var z=node.get("zones").get(id);String board=z.get("board").textValue();scope.expect(boards.containsKey(board),"BOARD_UNKNOWN","/space/zones/"+id,"Unknown zone board");
            double x=z.get("x").doubleValue(),y=z.get("y").doubleValue(),width=z.get("width").doubleValue(),height=z.get("height").doubleValue();
            scope.expect(x+width<=boards.get(board).width()&&y+height<=boards.get(board).height(),"ZONE_BOUNDS","/space/zones/"+id,"Zone exceeds board");
            zones.put(id,new SpatialRules.Zone(board,z.get("ownerSeat").intValue(),x,y,width,height));
        }
        return new SpatialRules(boards,bodies,movements,zones);
    }
    static ScenarioRules scenario(JsonNode root,CompileScope scope,Set<String> modules) {
        var node=root.path("scenario");if(!node.isMissingNode())scope.expect(modules.contains("scenario"),"MODULE_REQUIRED","/scenario","Scenario configuration requires scenario module");
        var rosters=new TreeMap<String,ScenarioRules.Roster>();var damage=new TreeMap<String,ScenarioRules.Damage>();
        for(String id:CompileScope.keys(node.path("rosters"))) {
            var r=node.get("rosters").get(id);String type=r.get("entityType").textValue(),path="/scenario/rosters/"+id;var entity=scope.entities.get(type);scope.expect(entity!=null,"ENTITY_TYPE_UNKNOWN",path,"Unknown roster type");
            for(String field:List.of("points","unique","faction")) {
                String name=r.get(field).textValue();if(name.isEmpty()&&!field.equals("points"))continue;
                var attribute=entity.attributes().get(name);scope.expect(attribute!=null&&attribute.type().equals(field.equals("points")?RuleType.INTEGER:RuleType.STRING),"ROSTER_FIELD",path+"/"+field,"Invalid roster binding");
            }
            int min=r.get("minimum").intValue(),max=r.get("maximum").intValue();scope.expect(min<=max&&max<=scope.limits.maxCollectionSize(),"ROSTER_BOUNDS",path,"Invalid roster size bounds");
            var factions=new ArrayList<String>();r.get("factions").forEach(f->factions.add(f.textValue()));
            rosters.put(id,new ScenarioRules.Roster(type,r.get("points").textValue(),r.get("budget").longValue(),min,max,r.get("unique").textValue(),r.get("faction").textValue(),factions));
        }
        for(String id:CompileScope.keys(node.path("damage"))) {
            var d=node.get("damage").get(id);String type=d.get("entityType").textValue(),path="/scenario/damage/"+id;var entity=scope.entities.get(type);scope.expect(entity!=null,"ENTITY_TYPE_UNKNOWN",path,"Unknown damage type");
            var absorbers=new ArrayList<String>();d.get("absorbers").forEach(a->absorbers.add(a.textValue()));var resources=new ArrayList<>(absorbers);resources.add(d.get("health").textValue());
            scope.expect(new HashSet<>(resources).size()==resources.size(),"DAMAGE_BINDING",path,"Damage resources must be distinct");
            for(String resource:resources)scope.expect(entity.resources().containsKey(resource)&&entity.resources().get(resource).minimum()==0,"DAMAGE_BINDING",path,"Damage resources require zero minimum");
            var onZero=ScenarioRules.OnZero.valueOf(d.get("onZero").textValue());String marker=d.get("marker").textValue();
            if(onZero==ScenarioRules.OnZero.MARK)scope.expect(entity.attributes().containsKey(marker)&&entity.attributes().get(marker).type().equals(RuleType.BOOLEAN),"DAMAGE_BINDING",path,"Death marker must be boolean");
            damage.put(id,new ScenarioRules.Damage(type,d.get("health").textValue(),absorbers,d.get("capAtRemaining").booleanValue(),onZero,marker));
        }
        return new ScenarioRules(rosters,damage);
    }
}
