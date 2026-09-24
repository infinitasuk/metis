package com.metis.engine.api;

import com.metis.engine.core.*;
import java.util.*;

/** Conservative order decisions using only the acting seat's projected view. */
final class CpuOrders {
    private CpuOrders() { }

    static Action plan(GameDefinition definition, GameState view, LegalAction action) {
        var unit=unit(view,action.parameters().get("unitId"));
        var profile=profile(definition,unit);
        var choices=definition.turnStructure().orders().orders();
        var preferred=choices.stream().filter(order -> order.orderEffects().stream().anyMatch(effect ->
                effect.kind()==OrderRules.Kind.RESTORE && !restoration(unit,profile,effect.amount().resolve(profile),effect.parameters()).isEmpty())).findFirst()
                .orElseGet(() -> choices.stream().filter(order -> order.orderEffects().stream().anyMatch(effect -> effect.kind()==OrderRules.Kind.ADD_ATTACK_DICE)).findFirst().orElse(choices.getFirst()));
        return new Action("plan_orders",unit.playerId(),Map.of("unitId",unit.unitId(),"orders",Collections.nCopies(((Number)action.parameters().get("count")).intValue(),preferred.code())));
    }

    static Action resolve(GameDefinition definition, GameState view, List<LegalAction> legal) {
        boolean canAttack=legal.stream().anyMatch(a->a.type().equals("attack_unit"));
        for (var action:legal.stream().filter(a->a.type().equals("resolve_order"))
                .sorted(Comparator.comparingInt(a->a.parameters().get("source").equals("both")?0:1)).toList()) {
            String timing=String.valueOf(action.parameters().get("timing"));
            if (timing.equals("BEFORE_MOVE") && canAttack || timing.equals("BEFORE_ATTACK") && !canAttack) continue;
            var unit=unit(view,action.parameters().get("unitId")); var profile=profile(definition,unit);
            var parameters=new HashMap<>(action.parameters());
            var effects=(List<?>)parameters.get("effects");
            int restore=0; Map<String,Object> restorePolicy=Map.of();
            for(Object item:effects) {
                var effect=ResponseWindows.object(item); int amount=((Number)effect.get("amount")).intValue();
                switch(String.valueOf(effect.get("kind"))) {
                    case "CHANGE_SPEED" -> parameters.put("speedDelta",unit.currentSpeed()==0 && profile.maximumSpeed()>0 ? Math.min(1,amount):0);
                    case "EXTRA_YAW" -> parameters.put("yawJoint",0);
                    case "RESTORE" -> { restore+=amount; restorePolicy=ResponseWindows.object(effect.get("parameters")); }
                    default -> { }
                }
            }
            if(restore>0) {
                var operations=restoration(unit,profile,restore,restorePolicy);
                if(operations.isEmpty()) continue;
                parameters.put("operations",operations);
            } else if(effects.stream().anyMatch(e->"RESTORE".equals(ResponseWindows.object(e).get("kind")))) continue;
            return new Action(action.type(),action.actorPlayerId(),parameters);
        }
        // Bank an unusable immediate order if there is space, preserving valuable stored tokens.
        for(var action:legal) if(action.type().equals("store_order") && !Boolean.TRUE.equals(action.parameters().get("replacementRequired"))) {
            String revealed=String.valueOf(OrderEngine.state(view,((Number)action.parameters().get("unitId")).longValue()).get("revealed"));
            if(definition.turnStructure().orders().orders().stream().anyMatch(o->o.code().equals(revealed) && o.timing()==OrderRules.Timing.ON_REVEAL))
                return new Action(action.type(),action.actorPlayerId(),action.parameters());
        }
        return null;
    }

    private static List<Map<String,Object>> restoration(UnitState unit,UnitDefinition profile,int budget,Map<String,Object> policy) {
        var result=new ArrayList<Map<String,Object>>(); var costs=ResponseWindows.object(policy.get("costs"));
        int healthCost=costs.get("health") instanceof Number n?n.intValue():0;
        int health=healthCost>0?Math.min(profile.maximumHullPoints()-unit.hullPoints(),budget/healthCost):0;
        if(health>0) { result.add(Map.of("resource","health","amount",health)); budget-=health*healthCost; }
        int shieldCost=costs.get("shield") instanceof Number n?n.intValue():0;
        if(shieldCost>0) for(String zone:new TreeSet<>(profile.shieldZones().keySet())) {
            int amount=Math.min(profile.shieldZones().get(zone)-unit.shieldZones().getOrDefault(zone,0),budget/shieldCost);
            if(amount>0) { result.add(Map.of("resource","shield","zone",zone,"amount",amount)); budget-=amount*shieldCost; }
        }
        return result;
    }
    private static UnitState unit(GameState state,Object id) { return state.units().stream().filter(u->u.unitId()==((Number)id).longValue()).findFirst().orElseThrow(); }
    private static UnitDefinition profile(GameDefinition definition,UnitState unit) { return definition.unitDefinitions().stream().filter(p->p.code().equals(unit.unitCode())).findFirst().orElseThrow(); }
}
