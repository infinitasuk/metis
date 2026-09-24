package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.*;

final class ScenarioMachine {
    private final RuntimeMachine machine;
    ScenarioMachine(RuntimeMachine machine) { this.machine=machine; }
    private EvaluationContext.Entity entity(RuleValue value) { machine.checked(value,RuleType.ENTITY,"/scenario",true);return machine.entities.get(((RuleValue.EntityRef)value.value()).id()); }
    private RuleValue attribute(EvaluationContext.Entity entity,String field) { var value=entity.attributes().get(field);require(value!=null,"ROSTER_FIELD","/scenario","Roster field unavailable");return value; }
    RuleValue execute(String operation,Map<String,RuleValue> args) {
        machine.budget.spend("/scenario");
        if(operation.equals("scenario.require")) { require(args.get("condition").bool(),"SCENARIO_REQUIREMENT","/scenario",(String)args.get("code").value());return null; }
        if(operation.equals("scenario.roster")) {
            var policy=machine.rules.scenario().rosters().get((String)args.get("policy").value());require(policy!=null,"ROSTER_POLICY","/scenario","Unknown roster policy");
            var members=args.get("entities").items();require(members.size()>=policy.minimum()&&members.size()<=policy.maximum(),"ROSTER_COUNT","/scenario","Roster size outside bounds");
            long total=0;var identities=new HashSet<String>();var unique=new HashSet<String>();
            for(var ref:members) {
                machine.budget.spend("/scenario");var entity=entity(ref);
                require(entity.type().equals(policy.entityType())&&Objects.equals(entity.owner(),args.get("owner").value())&&identities.add(entity.id()),"ROSTER_MEMBER","/scenario","Invalid roster member");
                long cost=attribute(entity,policy.points()).integer();
                require(cost>=0,"ROSTER_COST","/scenario","Negative roster cost");total=Math.addExact(total,cost);
                if(!policy.unique().isEmpty()) { var name=(String)attribute(entity,policy.unique()).value();require(name.isEmpty()||unique.add(name),"ROSTER_UNIQUE","/scenario","Unique roster entry repeated"); }
                if(!policy.faction().isEmpty()&&!policy.factions().isEmpty())require(policy.factions().contains((String)attribute(entity,policy.faction()).value()),"ROSTER_FACTION","/scenario","Faction is not allowed");
            }
            require(total<=policy.budget(),"ROSTER_BUDGET","/scenario","Roster exceeds points budget");return null;
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
