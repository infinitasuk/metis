package com.metis.engine.core;

import java.util.*;
import java.util.function.BiFunction;

/** Queues, consumable orders and reusable effects; no game identity checks. */
public final class OrderEngine {
    private final GameDefinition definition;
    private final OrderRules rules;
    public OrderEngine(GameDefinition definition) {
        this.definition = definition;
        this.rules = definition == null || definition.turnStructure() == null ? null : definition.turnStructure().orders();
    }
    private static Map<String,Object> object(Object value) { return ResponseWindows.object(value); }
    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(); }
    private UnitDefinition profile(UnitState unit) { return definition.unitDefinitions().stream().filter(p -> p.code().equals(unit.unitCode())).findFirst().orElse(null); }
    public boolean applies(UnitState unit) {
        if (rules == null) return false;
        var p = profile(unit);
        return rules != null && p != null && unit.hullPoints() > 0 && (rules.classifications().isEmpty()
                || rules.classifications().stream().anyMatch(c -> c.equalsIgnoreCase(p.classification()))) && rules.capacity().resolve(p) > 0;
    }
    public boolean planning(GameState state) { return rules != null && rules.planningPhase().equals(state.phaseCode()); }
    public boolean activation(GameState state, UnitState unit) { return rules != null && rules.activationPhase().equals(state.phaseCode()) && applies(unit); }
    public static Map<String,Object> state(GameState state, long id) { return object(object(state.values().get("orderState")).get(String.valueOf(id))); }
    public static Map<String,Object> queue(GameState state, long id) {
        var value = object(object(state.values().get("orderQueues")).get(String.valueOf(id)));
        return value.containsKey("$visibility") ? object(value.get("value")) : value;
    }
    public boolean needsPlanning(GameState state, UnitState unit) {
        return planning(state) && applies(unit) && strings(queue(state,unit.unitId()).get("entries")).size() < rules.capacity().resolve(profile(unit));
    }
    public boolean ready(GameState state) { return !planning(state) || state.units().stream().noneMatch(unit -> needsPlanning(state,unit)); }
    public List<LegalAction> planningActions(GameState state) {
        var result = new ArrayList<LegalAction>();
        if (!planning(state)) return result;
        for (var unit : state.units()) {
            if (!applies(unit)) continue;
            var queue = queue(state,unit.unitId()); int capacity = rules.capacity().resolve(profile(unit));
            int count = capacity - number(queue.get("locked"));
            if (count <= 0) continue;
            result.add(new LegalAction("plan_orders",unit.playerId(),"Plan orders for " + profile(unit).displayName(),
                    Map.of("unitId","number","orders","array"),Map.of("unitId",unit.unitId(),"count",count,"needed",Math.max(0,capacity-strings(queue.get("entries")).size()),
                    "locked",number(queue.get("locked")),"queue",strings(queue.get("entries")),
                    "choices",rules.orders().stream().map(o -> Map.of("code",o.code(),"label",o.label())).toList())));
        }
        return result;
    }
    public ActionResult plan(GameState state, Action action) {
        var unit = unit(state,action.parameters().get("unitId"));
        if (!planning(state) || unit == null || unit.playerId() != action.actorPlayerId() || !applies(unit)) return reject("ORDER_PLANNING_NOT_ALLOWED","Select an eligible unit you control during planning");
        Object input = action.parameters().get("orders");
        if (!(input instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String) || order(value.toString()) == null)) return reject("ORDER_INVALID","Choose defined orders");
        var queue = queue(state,unit.unitId()); var entries = strings(queue.get("entries")); int locked = number(queue.get("locked"));
        if (locked > entries.size() || list.size() != rules.capacity().resolve(profile(unit)) - locked) return reject("QUEUE_LENGTH_INVALID","Fill exactly the available queue slots; committed entries cannot be changed");
        var planned = new ArrayList<>(entries.subList(0,locked)); planned.addAll(strings(input));
        var next = setQueue(state,unit,Map.of("unitId",unit.unitId(),"entries",planned,"locked",locked));
        return ActivationEngine.event(state,next,"orders_planned",action.actorPlayerId(),ScopedData.owner(unit.playerId(),Map.of("unitId",unit.unitId(),"entries",planned)));
    }
    public GameState commit(GameState state) {
        if (!planning(state)) return state;
        for (var unit : state.units()) if (applies(unit)) {
            var queue = queue(state,unit.unitId()); queue.put("locked",strings(queue.get("entries")).size()); state = setQueue(state,unit,queue);
        }
        return state;
    }
    public GameState reveal(GameState s, UnitState unit) {
        if (!activation(s,unit)) return s;
        var queue = queue(s,unit.unitId()); var entries = new ArrayList<>(strings(queue.get("entries")));
        if (entries.isEmpty()) throw new IllegalArgumentException("The unit has no committed order to reveal");
        String revealed = entries.removeFirst(); queue.put("entries",entries); queue.put("locked",Math.max(0,number(queue.get("locked"))-1));
        var previous = state(s,unit.unitId());
        var record = new HashMap<String,Object>(); record.put("unitId",unit.unitId()); record.put("round",s.round());
        record.put("revealed",revealed); record.put("available",true); record.put("decisionOpen",true); record.put("revealWindow",true);
        record.put("tokens",object(previous.get("tokens"))); record.put("resolved",number(previous.get("round")) == s.round() ? strings(previous.get("resolved")) : List.of());
        return setState(setQueue(s,unit,queue),unit.unitId(),record);
    }
    private OrderRules.Order order(String code) { return rules == null ? null : rules.orders().stream().filter(o -> o.code().equals(code)).findFirst().orElse(null); }
    private int timingStep(GameState s, UnitState unit, OrderRules.Order order) {
        var context = ActivationContext.from(s);
        if (!activation(s,unit) || context.unitId() == null || context.unitId() != unit.unitId()) return -1;
        var record = state(s,unit.unitId());
        if (order.timing() == OrderRules.Timing.ON_REVEAL) return Boolean.TRUE.equals(record.get("revealWindow")) ? context.stepIndex() : -1;
        String action = order.timing() == OrderRules.Timing.BEFORE_MOVE ? "move_unit" : "attack_unit";
        var steps = definition.turnStructure().phase(s.phaseCode()).steps();
        for (int i=context.stepIndex();i<steps.size();i++) {
            var step=steps.get(i); int used=context.uses().getOrDefault(step.code(),0);
            if (step.actions().contains(action)) return used < (order.timing() == OrderRules.Timing.BEFORE_MOVE ? 1 : step.maximum()) ? i : -1;
            if (used < step.minimum()) return -1;
        }
        return -1;
    }
    public List<LegalAction> actions(GameState s, UnitState unit) {
        var result = new ArrayList<LegalAction>(); var record = state(s,unit.unitId());
        if (!activation(s,unit) || record.isEmpty()) return result;
        var tokens = object(record.get("tokens"));
        if (Boolean.TRUE.equals(record.get("available"))) {
            if (Boolean.TRUE.equals(record.get("decisionOpen")) && rules.tokenCapacity().resolve(profile(unit)) > 0 && number(tokens.get(record.get("revealed"))) < rules.perTypeLimit()) result.add(new LegalAction("store_order",unit.playerId(),"Bank revealed order",Map.of("unitId","number"),Map.of("unitId",unit.unitId(),"replaceChoices",tokens.keySet(),"replacementRequired",tokens.values().stream().mapToInt(OrderEngine::number).sum() >= rules.tokenCapacity().resolve(profile(unit)))));
            result.add(new LegalAction("discard_order",unit.playerId(),"Discard revealed order",Map.of("unitId","number"),Map.of("unitId",unit.unitId())));
        }
        for (var order : rules.orders()) {
            if (strings(record.get("resolved")).contains(order.code()) || timingStep(s,unit,order) < 0) continue;
            boolean dial = Boolean.TRUE.equals(record.get("available")) && order.code().equals(record.get("revealed"));
            boolean token = number(tokens.get(order.code())) > 0;
            var sources = new ArrayList<String>(); if (dial) sources.add("order"); if (token) sources.add("token"); if (dial && token) sources.add("both");
            for (String source : sources) {
                result.add(new LegalAction("resolve_order",unit.playerId(),order.label() + " / " + source,
                        Map.of("unitId","number","orderCode","string","source","string"),
                        Map.of("unitId",unit.unitId(),"orderCode",order.code(),"source",source,"timing",order.timing().name(),
                                "effects",effects(order,source).stream().map(e -> Map.of("kind",e.kind().name(),"amount",e.amount().resolve(profile(unit)),"parameters",e.parameters())).toList())));
            }
        }
        return result;
    }
    private List<OrderRules.Effect> effects(OrderRules.Order order,String source) {
        var result = new ArrayList<OrderRules.Effect>();
        if (source.equals("order") || source.equals("both")) result.addAll(order.orderEffects());
        if (source.equals("token") || source.equals("both")) result.addAll(order.tokenEffects());
        return result;
    }
    public ActionResult resolve(GameState s, Action action) {
        var unit=unit(s,action.parameters().get("unitId")); var context=ActivationContext.from(s);
        if (unit==null || unit.playerId()!=action.actorPlayerId() || context.unitId()==null || context.unitId()!=unit.unitId() || !activation(s,unit)) return reject("ORDER_NOT_ALLOWED","Use orders on the active unit you control");
        var record=state(s,unit.unitId()); var tokens=object(record.get("tokens"));
        if (action.type().equals("discard_order") || action.type().equals("store_order")) {
            if (!Boolean.TRUE.equals(record.get("available"))) return reject("ORDER_UNAVAILABLE","No revealed order is available");
            if (action.type().equals("store_order")) {
                if (!Boolean.TRUE.equals(record.get("decisionOpen"))) return reject("CONVERSION_WINDOW_CLOSED","Bank the order immediately after revealing it");
                String code=record.get("revealed").toString();
                if (number(tokens.get(code))>=rules.perTypeLimit()) return reject("TOKEN_TYPE_LIMIT","That token type is already at its limit");
                int cap=rules.tokenCapacity().resolve(profile(unit));
                if (cap < 1) return reject("TOKEN_CAPACITY","This unit cannot bank tokens");
                if (tokens.values().stream().mapToInt(OrderEngine::number).sum()>=cap) {
                    String replace=String.valueOf(action.parameters().get("replaceToken"));
                    if (number(tokens.get(replace))<1) return reject("TOKEN_CAPACITY","Choose an existing token to replace");
                    decrement(tokens,replace);
                }
                tokens.put(code,number(tokens.get(code))+1);
            }
            record.put("tokens",tokens); record.put("available",false); record.put("decisionOpen",false);
            return ActivationEngine.event(s,setState(s,unit.unitId(),record),action.type().equals("store_order")?"order_stored":"order_discarded",unit.playerId(),Map.of("unitId",unit.unitId(),"orderCode",record.get("revealed")));
        }
        String code=String.valueOf(action.parameters().get("orderCode")); var order=order(code);
        String source=String.valueOf(action.parameters().get("source"));
        if (order==null || !List.of("order","token","both").contains(source)) return reject("ORDER_INVALID","Choose a defined order and source");
        if (strings(record.get("resolved")).contains(code)) return reject("ORDER_ALREADY_RESOLVED","This order type has already resolved this round");
        int step=timingStep(s,unit,order);
        if (step<0) return reject("ORDER_TIMING","This order is not available at the current step");
        if (!source.equals("token") && (!Boolean.TRUE.equals(record.get("available")) || !code.equals(record.get("revealed")))) return reject("ORDER_UNAVAILABLE","The matching revealed order is unavailable");
        if (!source.equals("order") && number(tokens.get(code))<1) return reject("TOKEN_UNAVAILABLE","The matching token is unavailable");
        try {
            var selected=effects(order,source); var amounts=new EnumMap<OrderRules.Kind,Integer>(OrderRules.Kind.class);
            var policies=new EnumMap<OrderRules.Kind,Map<String,Object>>(OrderRules.Kind.class);
            for (var effect:selected) { amounts.merge(effect.kind(),effect.amount().resolve(profile(unit)),Math::addExact); policies.put(effect.kind(),effect.parameters()); }
            var modifiers=object(record.get("modifiers")); var updated=unit;
            if (amounts.containsKey(OrderRules.Kind.CHANGE_SPEED)) {
                int delta=integer(action.parameters().getOrDefault("speedDelta",0));
                int speed=Math.addExact(unit.currentSpeed(),delta);
                if (Math.abs((long)delta)>amounts.get(OrderRules.Kind.CHANGE_SPEED) || speed<0 || speed>profile(unit).maximumSpeed()) throw new IllegalArgumentException("Speed change is outside the available allowance");
                updated=resources(updated,speed,updated.hullPoints(),updated.shieldZones());
            }
            if (amounts.containsKey(OrderRules.Kind.EXTRA_YAW)) {
                int joint=integer(action.parameters().getOrDefault("yawJoint",0)); var chart=profile(unit).maneuverProfile(updated.currentSpeed());
                if (chart==null || joint<0 || joint>=chart.maximumYawClicks().size()) throw new IllegalArgumentException("Choose an available maneuver joint");
                modifiers.put("yawJoint",joint); modifiers.put("yawBonus",amounts.get(OrderRules.Kind.EXTRA_YAW));
                modifiers.put("yawMinimumSpeed",number(policies.get(OrderRules.Kind.EXTRA_YAW).get("minimumSpeed")));
                modifiers.put("yawMaximumClicks",number(policies.get(OrderRules.Kind.EXTRA_YAW).get("maximumClicks")));
            }
            if (amounts.containsKey(OrderRules.Kind.RESTORE)) updated=restore(updated,profile(unit),amounts.get(OrderRules.Kind.RESTORE),object(policies.get(OrderRules.Kind.RESTORE).get("costs")),action.parameters().get("operations"));
            if (amounts.containsKey(OrderRules.Kind.ADD_ATTACK_DICE)) modifiers.put("attackDice",amounts.get(OrderRules.Kind.ADD_ATTACK_DICE));
            if (amounts.containsKey(OrderRules.Kind.REROLL_ATTACK_DICE)) modifiers.put("rerolls",amounts.get(OrderRules.Kind.REROLL_ATTACK_DICE));
            if (amounts.containsKey(OrderRules.Kind.ADD_ATTACK_DICE)) {
                String color=String.valueOf(action.parameters().getOrDefault("color","auto"));
                boolean supported=java.util.stream.Stream.concat(profile(unit).batteryArcs().values().stream().flatMap(List::stream),profile(unit).antiSquadronDice().stream()).anyMatch(die->die.color().equals(color));
                if (!color.equals("auto") && !supported) throw new IllegalArgumentException("Choose a color present in this unit's weapons");
                modifiers.put("color",color);
            }
            if (amounts.containsKey(OrderRules.Kind.GRANT_ACTIVATIONS)) {
                var grant=new HashMap<>(policies.get(OrderRules.Kind.GRANT_ACTIVATIONS));
                grant.put("remaining",amounts.get(OrderRules.Kind.GRANT_ACTIVATIONS)); grant.put("completed",List.of());
                record.put("grant",grant);
            }
            record.put("modifiers",modifiers); record.put("decisionOpen",false);
            if (order.timing()!=OrderRules.Timing.ON_REVEAL) record.put("revealWindow",false);
            if (!source.equals("token")) record.put("available",false);
            if (!source.equals("order")) decrement(tokens,code);
            record.put("tokens",tokens); var resolved=new ArrayList<>(strings(record.get("resolved"))); resolved.add(code); record.put("resolved",resolved);
            GameState next=replace(setState(s,unit.unitId(),record),updated);
            var values=new HashMap<>(next.values()); values.put(ActivationContext.KEY,new ActivationContext(context.unitId(),step,context.uses(),context.distinctValues(),context.completedUnits(),context.completedPlayers(),context.groupCompleted()).toMap());
            next=copy(next,values,next.units());
            return ActivationEngine.event(s,next,"order_resolved",unit.playerId(),Map.of("unitId",unit.unitId(),"orderCode",code,"source",source));
        } catch (IllegalArgumentException | ArithmeticException error) { return reject("ORDER_EFFECT_INVALID",error.getMessage()); }
    }
    private static UnitState restore(UnitState unit,UnitDefinition profile,int budget,Map<String,Object> costs,Object input) {
        if (!(input instanceof List<?> operations) || operations.isEmpty()) throw new IllegalArgumentException("Choose restoration operations");
        int health=unit.hullPoints(); var zones=new HashMap<>(unit.shieldZones()); int spent=0;
        for (Object item:operations) {
            var op=object(item); String resource=String.valueOf(op.get("resource")); int amount=integer(op.get("amount"));
            int cost=number(costs.get(resource));
            if (amount<=0 || cost<=0) throw new IllegalArgumentException("Invalid restoration operation");
            spent=Math.addExact(spent,Math.multiplyExact(amount,cost));
            if (spent>budget) throw new IllegalArgumentException("Restoration budget exceeded");
            if (resource.equals("health")) {
                health=Math.addExact(health,amount);
                if (health>profile.maximumHullPoints()) throw new IllegalArgumentException("Health cannot exceed its maximum");
            } else {
                String to=String.valueOf(op.get(resource.equals("transfer")?"to":"zone"));
                if (!zones.containsKey(to) || !profile.shieldZones().containsKey(to)) throw new IllegalArgumentException("Unknown defense zone");
                if (resource.equals("transfer")) {
                    String from=String.valueOf(op.get("from"));
                    if (from.equals(to) || zones.getOrDefault(from,0)<amount) throw new IllegalArgumentException("Not enough shields to transfer");
                    zones.put(from,zones.get(from)-amount);
                }
                int total=Math.addExact(zones.get(to),amount);
                if (total>profile.shieldZones().get(to)) throw new IllegalArgumentException("Shields cannot exceed their maximum");
                zones.put(to,total);
            }
        }
        return resources(unit,unit.currentSpeed(),health,zones);
    }
    public static int integer(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.intValue()) throw new IllegalArgumentException("An integer is required");
        return n.intValue();
    }
    public static Map<String,Object> modifiers(GameState s,long id) { return object(state(s,id).get("modifiers")); }
    public static int modifier(GameState s,long id,String key) { return number(modifiers(s,id).get(key)); }
    public static int yawLimit(GameState s,long id,int speed,int joint,int base) {
        if (speed<modifier(s,id,"yawMinimumSpeed") || joint!=modifier(s,id,"yawJoint")) return base;
        int maximum=modifier(s,id,"yawMaximumClicks");
        return (int)Math.min(maximum>0?maximum:Integer.MAX_VALUE,(long)base+modifier(s,id,"yawBonus"));
    }
    public static GameState afterAction(GameState s,Action action) {
        if (!(action.parameters().get("unitId") instanceof Number id)) return s;
        var record=state(s,id.longValue()); if(record.isEmpty()) return s;
        record.put("decisionOpen",false); record.put("revealWindow",false);
        if(action.type().equals("attack_unit")) {
            var modifiers=object(record.get("modifiers")); modifiers.remove("attackDice"); modifiers.remove("rerolls"); modifiers.remove("color"); record.put("modifiers",modifiers);
        }
        return setState(s,id.longValue(),record);
    }
    public static GameState close(GameState s,long id) {
        var record=state(s,id); if(record.isEmpty()) return s;
        record.put("available",false); record.put("decisionOpen",false); record.put("revealWindow",false); record.remove("modifiers"); record.remove("grant");
        return setState(s,id,record);
    }
    public static boolean spentActivation(GameState s,long id) {
        Object round=object(object(s.values().get("orderActivations")).get(String.valueOf(id))).get("round");
        return round instanceof Number n && n.intValue()==s.round();
    }
    public static Map<String,Object> grant(GameState s) {
        Long parent=ActivationContext.from(s).unitId(); return parent==null?Map.of():object(state(s,parent).get("grant"));
    }
    public static boolean activeSupport(GameState s,long unit) { return grant(s).get("childId") instanceof Number n && n.longValue()==unit; }
    public List<UnitState> supportCandidates(GameState s) {
        var grant=grant(s); if(grant.isEmpty() || number(grant.get("remaining"))<=0) return List.of();
        var parent=unit(s,ActivationContext.from(s).unitId()); if(parent==null) return List.of();
        return s.units().stream().filter(u->u.playerId()==parent.playerId() && u.unitId()!=parent.unitId() && u.hullPoints()>0 && !spentActivation(s,u.unitId())
                && !"activated".equals(u.status()) && profile(u)!=null && !applies(u))
                .filter(u->strings(grant.get("classifications")).isEmpty() || strings(grant.get("classifications")).stream().anyMatch(c->profile(u)!=null&&c.equalsIgnoreCase(profile(u).classification())))
                .filter(u->Math.hypot(u.positionX()-parent.positionX(),u.positionY()-parent.positionY())<=((Number)grant.get("range")).doubleValue()).toList();
    }
    public List<LegalAction> supportActions(GameState s) {
        var result=new ArrayList<LegalAction>(); var grant=grant(s); if(grant.isEmpty()) return result;
        if(!(grant.get("childId") instanceof Number id)) {
            for(var unit:supportCandidates(s)) result.add(new LegalAction("activate_support_unit",unit.playerId(),"Activate coordinated unit "+unit.unitId(),Map.of("unitId","number"),Map.of("unitId",unit.unitId())));
            result.add(new LegalAction("end_order_grant",s.activePlayerId(),"Finish coordinated activations",Map.of()));
        } else {
            var used=strings(grant.get("used"));
            for(String action:strings(grant.get("actions"))) if(!used.contains(action) || (action.equals("move_unit") && editableSupportMove(s,id.longValue()))) result.add(new LegalAction(action,s.activePlayerId(),action+" for coordinated unit "+id.longValue(),Map.of("unitId","number"),Map.of("unitId",id.longValue())));
            if(editableSupportMove(s,id.longValue())) result.add(new LegalAction("reset_movement",s.activePlayerId(),"Reset movement for unit "+id.longValue(),Map.of("unitId","number"),Map.of("unitId",id.longValue())));
            result.add(new LegalAction("end_support_activation",s.activePlayerId(),"Finish coordinated unit",Map.of()));
        }
        return result;
    }
    public ActionResult support(GameState s,Action action,BiFunction<GameState,Action,ActionResult> effects) {
        if(!ActionAuthorization.isActivePlayer(s,action.actorPlayerId())) return reject("NOT_ACTIVE_PLAYER","It is not your turn");
        long parent=ActivationContext.from(s).unitId(); var record=state(s,parent); var grant=object(record.get("grant"));
        Long child=grant.get("childId") instanceof Number n?n.longValue():null;
        if(action.type().equals("end_order_grant") && child==null) { record.remove("grant"); return ActivationEngine.event(s,setState(s,parent,record),"order_grant_ended",action.actorPlayerId(),Map.of("unitId",parent)); }
        if(action.type().equals("activate_support_unit") && child==null) {
            var target=unit(s,action.parameters().get("unitId"));
            long targetId=target==null?-1:target.unitId();
            if(target==null || supportCandidates(s).stream().noneMatch(u->u.unitId()==targetId)) return reject("SUPPORT_TARGET_INVALID","Choose an eligible nearby unit you control");
            grant.put("childId",target.unitId()); grant.put("used",List.of()); record.put("grant",grant);
            if(Boolean.TRUE.equals(grant.get("useMaximumSpeed"))) target=resources(target,profile(target).maximumSpeed(),target.hullPoints(),target.shieldZones());
            return ActivationEngine.event(s,replace(setState(s,parent,record),withStatus(target,"activating")),"support_activated",action.actorPlayerId(),Map.of("unitId",target.unitId(),"parentUnitId",parent));
        }
        if(child==null) return reject("SUPPORT_REQUIRED","Choose or finish coordinated activations before continuing");
        if(action.type().equals("end_support_activation")) {
            var target=unit(s,child); grant.remove("childId"); grant.remove("used"); grant.put("remaining",number(grant.get("remaining"))-1); record.put("grant",grant);
            var next=replace(setState(s,parent,record),withStatus(target,"activated")); var values=new HashMap<>(next.values()); var spent=object(values.get("orderActivations"));
            spent.put(String.valueOf(child),Map.of("unitId",child,"round",s.round())); values.put("orderActivations",spent); next=copy(next,values,next.units());
            return ActivationEngine.event(s,next,"support_completed",action.actorPlayerId(),Map.of("unitId",child,"parentUnitId",parent));
        }
        boolean editing=editableSupportMove(s,child);
        if(!(strings(grant.get("actions")).contains(action.type()) || action.type().equals("reset_movement")) || !(action.parameters().get("unitId") instanceof Number id) || id.longValue()!=child
                || (action.type().equals("reset_movement") && !editing)
                || (strings(grant.get("used")).contains(action.type()) && !(action.type().equals("move_unit") && editing))) return reject("SUPPORT_ACTION_INVALID","Use each coordinated action at most once, on the active coordinated unit");
        var result=effects.apply(s,action); if(!result.accepted() || result.code().equals("RESPONSE_REQUIRED")) return result;
        var used=new ArrayList<>(strings(grant.get("used")));
        if(action.type().equals("reset_movement")) used.remove("move_unit"); else if(!used.contains(action.type())) used.add(action.type());
        grant.put("used",used); record.put("grant",grant);
        var next=replace(setState(result.resultingState(),parent,record),withStatus(unit(result.resultingState(),child),"activating"));
        var event=result.resultingState().events().getLast();
        return ActivationEngine.event(s,next,event.type(),action.actorPlayerId(),event.payload());
    }
    private boolean editableSupportMove(GameState s,long id) {
        if(s.players().stream().noneMatch(p->p.playerId()==s.activePlayerId() && "human".equals(p.kind()))) return false;
        return strings(grant(s).get("used")).contains("move_unit") && s.events().stream()
                .filter(e->e.payload().get("unitId") instanceof Number n && n.longValue()==id).reduce((a,b)->b).map(e->e.type().equals("unit_moved")).orElse(false);
    }
    private static void decrement(Map<String,Object> values,String key) { int left=number(values.get(key))-1; if(left<=0) values.remove(key); else values.put(key,left); }
    private static UnitState unit(GameState state,Object id) { return id instanceof Number n?state.units().stream().filter(u->u.unitId()==n.longValue()).findFirst().orElse(null):null; }
    private static UnitState resources(UnitState u,int speed,int health,Map<String,Integer> zones) {
        return new UnitState(u.unitId(),u.playerId(),u.unitCode(),u.positionX(),u.positionY(),u.facing(),u.status(),health,
                zones.isEmpty()?u.shieldPoints():zones.values().stream().mapToInt(Integer::intValue).sum(),u.defenseTokens(),speed,zones,u.readyDefenseTokens(),u.exhaustedDefenseTokens());
    }
    private static UnitState withStatus(UnitState u,String status) { return u.hullPoints()==0?u:new UnitState(u.unitId(),u.playerId(),u.unitCode(),u.positionX(),u.positionY(),u.facing(),status,u.hullPoints(),u.shieldPoints(),u.defenseTokens(),u.currentSpeed(),u.shieldZones(),u.readyDefenseTokens(),u.exhaustedDefenseTokens()); }
    private static GameState replace(GameState s,UnitState unit) { return copy(s,s.values(),s.units().stream().map(u->u.unitId()==unit.unitId()?unit:u).toList()); }
    private static GameState setQueue(GameState s,UnitState unit,Map<String,Object> queue) {
        var values=new HashMap<>(s.values()); var queues=object(values.get("orderQueues")); queues.put(String.valueOf(unit.unitId()),ScopedData.owner(unit.playerId(),queue)); values.put("orderQueues",queues); return copy(s,values,s.units());
    }
    private static GameState setState(GameState s,long id,Map<String,Object> record) {
        var values=new HashMap<>(s.values()); var orders=object(values.get("orderState")); orders.put(String.valueOf(id),record); values.put("orderState",orders); return copy(s,values,s.units());
    }
    private static GameState copy(GameState s,Map<String,Object> values,List<UnitState> units) { return new GameState(s.sessionId(),s.definitionVersionId(),s.status(),s.round(),s.phaseCode(),s.activePlayerId(),s.stateVersion(),s.players(),units,s.board(),s.events(),values); }
    private static ActionResult reject(String code,String message) { return new ActionResult(false,code,message,null); }
}
