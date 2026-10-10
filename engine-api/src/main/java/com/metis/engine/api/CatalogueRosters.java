package com.metis.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.metis.engine.core.rules.*;
import com.metis.engine.core.rules.runtime.RulesState;

import java.util.*;

/** Pre-game adapter over immutable, imported definition data; never accepts client-authored costs. */
public final class CatalogueRosters {
    public record Unit(String code,String name,String faction,long points,String uniqueKey,String provenance) { }
    public record Policy(String code,long budget,int minimum,int maximum,List<String> factions,List<String> units) { }
    public record Seat(int seat,String policy,long budget,int minimum,int maximum,List<String> factions,List<String> units,List<Policy> choices) { }
    public record View(boolean available,boolean selectable,String fingerprint,List<Unit> units,List<Seat> seats) { }
    public record Validation(boolean valid,String code,Long points,long budget,String fingerprint) { }
    public record SelectedSeat(int seat,String playerId,String policy,long points,long budget,List<Unit> units,List<String> entityIds) { }
    public record Capture(String fingerprint,String definition,String revision,List<SelectedSeat> seats) { }
    private final CompiledRules definition;
    private final View view;

    CatalogueRosters(String source) {
        this(compile(source));
    }
    private static CompiledRules compile(String source) {
        var result=new RulesCompiler().compile(source);
        if(!result.valid())throw new RulesService.Failure(409,"SNAPSHOT_INCOMPATIBLE");
        return result.definition();
    }
    CatalogueRosters(CompiledRules definition) {
        this.definition=definition;
        if(!definition.state().containsKey("rosterPolicies")) {
            view=new View(false,false,definition.fingerprint(),List.of(),List.of());return;
        }
        boolean selectable=definition.state().containsKey("selectedRosters");
        var policies=values("rosterPolicies");var ids=values("catalogueIds");var units=new ArrayList<Unit>();var seats=new ArrayList<Seat>();
        configured(!policies.isEmpty()&&!ids.isEmpty());
        for(String field:List.of("names","factions","points","uniqueKeys","pointSources"))configured(values(field).size()==ids.size());
        for(int index=0;index<ids.size();index++) {
            configured(values("points").get(index).type().equals(RuleType.INTEGER));
            units.add(new Unit(text("catalogueIds",index),text("names",index),text("factions",index),values("points").get(index).integer(),
                    text("uniqueKeys",index),text("pointSources",index)));
        }
        if(selectable) {
            for(String field:List.of("selectedRosters","selectedPolicies","rosterPolicyChoices","rosterEntityIds"))configured(values(field).size()==policies.size());
            configured(definition.state().get("selectedRosters").type().equals(RuleType.list(RuleType.list(RuleType.INTEGER))));
            configured(definition.state().get("selectedPolicies").type().equals(RuleType.list(RuleType.STRING)));
            var entityIds=new HashSet<String>();
            for(var row:values("rosterEntityIds"))for(var id:row.items())configured(id.type().equals(RuleType.STRING)&&!((String)id.value()).isBlank()&&entityIds.add((String)id.value()));
        }
        for(int index=0;index<policies.size();index++) {
            String code=text("rosterPolicies",index);var policy=definition.scenario().rosters().get(code);
            configured(policy!=null&&(selectable||index<units.size()));
            var choices=new ArrayList<Policy>();
            var allowed=selectable?values("rosterPolicyChoices").get(index).items():List.of(RuleValue.text(code));
            for(var option:allowed) {
                configured(option.type().equals(RuleType.STRING));String name=(String)option.value();var choice=definition.scenario().rosters().get(name);configured(choice!=null);
                if(selectable)configured(choice.maximum()<=values("rosterEntityIds").get(index).items().size());
                choices.add(new Policy(name,choice.budget(),choice.minimum(),choice.maximum(),choice.factions(),units.stream()
                        .filter(unit->choice.factions().isEmpty()||choice.factions().contains(unit.faction())).map(Unit::code).toList()));
            }
            configured(choices.stream().anyMatch(choice->choice.code().equals(code))&&choices.stream().map(Policy::code).distinct().count()==choices.size());
            var defaults=selectable?selectedUnits(values("selectedRosters").get(index).items(),units).stream().map(Unit::code).toList():List.of(units.get(index).code());
            seats.add(new Seat(index,code,policy.budget(),policy.minimum(),policy.maximum(),policy.factions(),defaults,List.copyOf(choices)));
        }
        configured(units.stream().map(Unit::code).distinct().count()==units.size());
        view=new View(true,selectable,definition.fingerprint(),List.copyOf(units),List.copyOf(seats));
    }
    private static void configured(boolean condition) { if(!condition)throw new RulesService.Failure(422,"CATALOGUE_ROSTER_INVALID"); }
    private List<RuleValue> values(String field) {
        var declaration=definition.state().get(field);
        configured(declaration!=null&&declaration.visibility()==CompiledRules.Audience.PUBLIC&&declaration.type().kind()==RuleType.Kind.LIST);
        return declaration.initial().items();
    }
    private String text(String field,int index) { var value=values(field).get(index);configured(value.type().equals(RuleType.STRING));return (String)value.value(); }
    private static List<Unit> selectedUnits(List<RuleValue> indices,List<Unit> units) {
        var result=new ArrayList<Unit>();
        for(var index:indices) { configured(index.type().equals(RuleType.INTEGER)&&index.integer()>=0&&index.integer()<units.size());result.add(units.get((int)index.integer())); }
        return List.copyOf(result);
    }
    public View view() { return view; }

    public Validation validateRequest(JsonNode request) {
        var fields=new HashSet<String>();if(request!=null)request.fieldNames().forEachRemaining(fields::add);
        if(request==null||!request.isObject()||!fields.equals(request.has("policy")?Set.of("seat","policy","units"):Set.of("seat","units")))throw new RulesService.Failure(400,"REQUEST_FIELDS_INVALID");
        if(!request.get("seat").isIntegralNumber()||!request.get("seat").canConvertToInt())throw new RulesService.Failure(400,"ROSTER_SEAT_INVALID");
        if(request.has("policy")&&!request.get("policy").isTextual())throw new RulesService.Failure(400,"ROSTER_INPUT_INVALID");
        return validate(request.get("seat").intValue(),request.has("policy")?request.get("policy").textValue():null,request.get("units"));
    }

    public Validation validate(int seat,JsonNode selection) {
        return validate(seat,null,selection);
    }
    public Validation validate(int seat,String policyCode,JsonNode selection) {
        if(!view.available())throw new RulesService.Failure(409,"CATALOGUE_ROSTER_UNAVAILABLE");
        if(seat<0||seat>=view.seats().size())throw new RulesService.Failure(400,"ROSTER_SEAT_INVALID");
        if(selection==null||!selection.isArray()||selection.size()>definition.limits().maxCollectionSize())throw new RulesService.Failure(400,"ROSTER_INPUT_INVALID");
        String code=policyCode==null?view.seats().get(seat).policy():policyCode;
        if(view.seats().get(seat).choices().stream().noneMatch(choice->choice.code().equals(code)))throw new RulesService.Failure(422,"ROSTER_POLICY");
        var policy=definition.scenario().rosters().get(code);
        var entities=new ArrayList<EvaluationContext.Entity>();long total=0;
        try {
            for(var selected:selection) {
                if(!selected.isTextual())throw new RulesService.Failure(400,"ROSTER_INPUT_INVALID");
                var unit=view.units().stream().filter(item->item.code().equals(selected.textValue())).findFirst().orElse(null);
                if(unit==null)return new Validation(false,"ROSTER_UNIT_UNKNOWN",null,policy.budget(),view.fingerprint());
                total=Math.addExact(total,unit.points());
                var attributes=new TreeMap<String,RuleValue>();attributes.put(policy.points(),RuleValue.integer(unit.points()));
                if(!policy.faction().isEmpty())attributes.put(policy.faction(),RuleValue.text(unit.faction()));
                if(!policy.unique().isEmpty())attributes.put(policy.unique(),RuleValue.text(unit.uniqueKey()));
                entities.add(new EvaluationContext.Entity(policy.entityType(),"entry-"+entities.size(),"roster",attributes,Map.of()));
            }
            RosterValidator.validate(policy,"roster",entities);
            return new Validation(true,"ROSTER_VALID",total,policy.budget(),view.fingerprint());
        } catch(RuleEvaluationException error) {
            return new Validation(false,error.code(),total,policy.budget(),view.fingerprint());
        } catch(ArithmeticException error) {
            return new Validation(false,"NUMERIC_ERROR",null,policy.budget(),view.fingerprint());
        }
    }
    /** Only these two declared setup fields are bound from validated profile codes. */
    RulesState select(RulesState initial,JsonNode selections) {
        if(!view.selectable()) {
            if(selections!=null)throw new RulesService.Failure(400,"ROSTER_SELECTION_UNSUPPORTED");
            if(view.available()) {
                if(initial.players().size()!=view.seats().size())throw new RulesService.Failure(422,"ROSTER_SEAT_COUNT");
                validateDefaults();
            }
            return initial;
        }
        if(initial.version()!=0||initial.control().status()!=RulesState.Status.SETUP)throw new RulesService.Failure(409,"ROSTER_ALREADY_CAPTURED");
        if(selections==null)throw new RulesService.Failure(422,"ROSTER_REQUIRED");
        if(!selections.isArray()||selections.size()!=view.seats().size()||initial.players().size()!=view.seats().size())throw new RulesService.Failure(422,"ROSTER_SEAT_COUNT");
        var rows=new ArrayList<RuleValue>();var policies=new ArrayList<RuleValue>();
        for(int seat=0;seat<selections.size();seat++) {
            var selection=selections.get(seat);var fields=new HashSet<String>();selection.fieldNames().forEachRemaining(fields::add);
            if(!selection.isObject()||!fields.equals(Set.of("policy","units"))||!selection.path("policy").isTextual())throw new RulesService.Failure(400,"ROSTER_INPUT_INVALID");
            String policy=selection.get("policy").textValue();var result=validate(seat,policy,selection.get("units"));
            if(!result.valid())throw new RulesService.Failure(422,result.code(),List.of(new RuleDiagnostic(result.code(),definition.id(),null,"/rosters/"+seat,
                    "Seat "+(seat+1)+": "+result.code()+" ("+result.points()+" / "+result.budget()+" points)")));
            var indices=new ArrayList<RuleValue>();
            for(var code:selection.get("units")) {
                int index=0;while(!view.units().get(index).code().equals(code.textValue()))index++;
                indices.add(RuleValue.integer(index));
            }
            rows.add(RuleValue.list(RuleType.INTEGER,indices));policies.add(RuleValue.text(policy));
        }
        var globals=new TreeMap<>(initial.globals());globals.put("selectedRosters",RuleValue.list(RuleType.list(RuleType.INTEGER),rows));
        globals.put("selectedPolicies",RuleValue.list(RuleType.STRING,policies));
        return new RulesState(initial.runtimeVersion(),initial.definitionFingerprint(),initial.sessionId(),initial.version(),initial.players(),globals,initial.entities(),initial.control(),initial.events());
    }
    /** Read from the immutable initial checkpoint, even after units are destroyed or a new revision is published. */
    Capture captured(RulesState initial) {
        if(!view.available())return null;
        var seats=new ArrayList<SelectedSeat>();
        for(var seat:view.seats()) {
            String policy=seat.policy();List<Unit> units;
            if(view.selectable()) {
                policy=(String)initial.globals().get("selectedPolicies").items().get(seat.seat()).value();
                units=selectedUnits(initial.globals().get("selectedRosters").items().get(seat.seat()).items(),view.units());
            } else units=seat.units().stream().map(code->view.units().stream().filter(unit->unit.code().equals(code)).findFirst().orElseThrow()).toList();
            var requested=JsonNodeFactory.instance.arrayNode();units.forEach(unit->requested.add(unit.code()));var report=validate(seat.seat(),policy,requested);
            configured(report.valid());
            var ids=view.selectable()?values("rosterEntityIds").get(seat.seat()).items().subList(0,units.size()).stream().map(value->(String)value.value()).toList()
                    :List.of(initial.players().get(seat.seat()));
            seats.add(new SelectedSeat(seat.seat(),initial.players().get(seat.seat()),policy,report.points(),report.budget(),units,ids));
        }
        return new Capture(view.fingerprint(),definition.id(),definition.revision(),List.copyOf(seats));
    }
    void validateDefaults() {
        for(var seat:view.seats()) {
            var selection=JsonNodeFactory.instance.arrayNode();seat.units().forEach(selection::add);
            var result=validate(seat.seat(),selection);
            if(!result.valid())throw new RulesService.Failure(422,result.code());
        }
    }
}
