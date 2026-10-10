package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.*;

final class ScenarioMachine {
    private final RuntimeMachine machine;
    ScenarioMachine(RuntimeMachine machine) { this.machine=machine; }
    private EvaluationContext.Entity entity(RuleValue value) { machine.checked(value,RuleType.ENTITY,"/scenario",true);return machine.entities.get(((RuleValue.EntityRef)value.value()).id()); }
    RuleValue execute(String operation,Map<String,RuleValue> args) {
        machine.budget.spend("/scenario");
        if(operation.equals("scenario.require")) { require(args.get("condition").bool(),"SCENARIO_REQUIREMENT","/scenario",(String)args.get("code").value());return null; }
        if(operation.equals("scenario.roster")) {
            var policy=machine.rules.scenario().rosters().get((String)args.get("policy").value());require(policy!=null,"ROSTER_POLICY","/scenario","Unknown roster policy");
            var members=args.get("entities").items();require(members.size()>=policy.minimum()&&members.size()<=policy.maximum(),"ROSTER_COUNT","/scenario","Roster size outside bounds");
            var entities=new ArrayList<EvaluationContext.Entity>();
            for(var ref:members) {
                machine.budget.spend("/scenario");entities.add(entity(ref));
            }
            RosterValidator.validate(policy,(String)args.get("owner").value(),entities);return null;
        }
        require(operation.equals("scenario.damage"),"OPERATION_UNAVAILABLE","/scenario","Unknown scenario operation");
        var target=entity(args.get("target"));var policy=machine.rules.scenario().damage().get((String)args.get("policy").value());
        require(policy!=null&&target.type().equals(policy.entityType()),"DAMAGE_POLICY","/scenario","Damage policy does not match target");
        long remaining=args.get("amount").integer();require(remaining>=0,"DAMAGE_AMOUNT","/scenario","Damage must not be negative");var resources=new TreeMap<>(target.resources());
        for(String absorber:policy.absorbers()) {
            require(resources.containsKey(absorber),"DAMAGE_FIELD","/scenario","Damage resource unavailable");long absorbed=Math.min(remaining,resources.get(absorber));remaining-=absorbed;resources.put(absorber,resources.get(absorber)-absorbed);
        }
        require(resources.containsKey(policy.health()),"DAMAGE_FIELD","/scenario","Health resource unavailable");long health=resources.get(policy.health()),damage=policy.capAtRemaining()?Math.min(health,remaining):remaining;
        long after=Math.subtractExact(health,damage);resource(machine.rules.entities().get(target.type()).resources().get(policy.health()),after,"/scenario");resources.put(policy.health(),after);
        var attributes=new TreeMap<>(target.attributes());
        if(after==0&&policy.onZero()==ScenarioRules.OnZero.MARK)attributes.put(policy.marker(),RuleValue.bool(false));
        if(after==0&&policy.onZero()==ScenarioRules.OnZero.REMOVE) { machine.entities.remove(target.id());if(machine.mechanics!=null)machine.mechanics.entityRemoved(target.id()); }
        else machine.entities.put(target.id(),new EvaluationContext.Entity(target.type(),target.id(),target.owner(),attributes,resources));
        machine.system("damageResolved","/scenario");return RuleValue.integer(damage);
    }
}
