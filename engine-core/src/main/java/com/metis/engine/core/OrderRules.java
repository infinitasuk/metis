package com.metis.engine.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional, definition-owned queued-order mechanics. Names and bindings are data. */
public record OrderRules(String planningPhase, String activationPhase, List<String> classifications,
                         Amount capacity, Amount tokenCapacity, int perTypeLimit, List<Order> orders) {
    public enum Timing { ON_REVEAL, BEFORE_MOVE, BEFORE_ATTACK }
    public enum Kind { CHANGE_SPEED, EXTRA_YAW, RESTORE, ADD_ATTACK_DICE, REROLL_ATTACK_DICE, GRANT_ACTIVATIONS }
    public OrderRules {
        if (planningPhase == null || activationPhase == null || capacity == null || tokenCapacity == null || perTypeLimit < 1 || orders == null || orders.isEmpty()) throw new IllegalArgumentException("Invalid order rules");
        classifications = classifications == null ? List.of() : List.copyOf(classifications);
        orders = List.copyOf(orders);
        if (orders.stream().map(Order::code).distinct().count() != orders.size()) throw new IllegalArgumentException("Duplicate order code");
    }
    public record Amount(String stat, int value, int divisor, boolean roundUp) {
        private static final Set<String> STATS = Set.of("commandValue", "engineeringValue", "squadronValue", "maximumSpeed", "maximumHullPoints");
        public Amount {
            if (stat != null && !STATS.contains(stat)) throw new IllegalArgumentException("Unsupported profile binding: " + stat);
            if (value < 0 || divisor < 0) throw new IllegalArgumentException("Invalid amount");
            divisor = divisor == 0 ? 1 : divisor;
        }
        public int resolve(UnitDefinition profile) {
            int base = stat == null ? value : switch (stat) {
                case "commandValue" -> profile.commandValue();
                case "engineeringValue" -> profile.engineeringValue();
                case "squadronValue" -> profile.squadronValue();
                case "maximumSpeed" -> profile.maximumSpeed();
                case "maximumHullPoints" -> profile.maximumHullPoints();
                default -> throw new IllegalArgumentException("Unsupported binding");
            };
            return roundUp ? (int) Math.ceil((double) base / divisor) : base / divisor;
        }
    }
    public record Effect(Kind kind, Amount amount, Map<String, Object> parameters) {
        public Effect {
            if (kind == null || amount == null) throw new IllegalArgumentException("Effect kind and amount are required");
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            if (kind == Kind.RESTORE) {
                Object costs = parameters.get("costs");
                if (!(costs instanceof Map<?, ?> map) || map.isEmpty() || map.entrySet().stream().anyMatch(entry ->
                        !Set.of("health", "shield", "transfer").contains(entry.getKey()) || !(entry.getValue() instanceof Number n)
                                || n.doubleValue() != n.intValue() || n.intValue() <= 0)) throw new IllegalArgumentException("Invalid restoration costs");
            }
            if (kind == Kind.GRANT_ACTIVATIONS) {
                if (!(parameters.get("range") instanceof Number range) || !Double.isFinite(range.doubleValue()) || range.doubleValue() < 0
                        || !(parameters.get("classifications") instanceof List<?> classes) || classes.stream().anyMatch(c -> !(c instanceof String))
                        || !(parameters.get("actions") instanceof List<?> actions) || actions.isEmpty()
                        || actions.stream().anyMatch(a -> !Set.of("move_unit", "attack_unit").contains(a))) throw new IllegalArgumentException("Grant range, classifications and supported actions are required");
            }
            if (kind == Kind.EXTRA_YAW) {
                for (String key : List.of("minimumSpeed", "maximumClicks")) {
                    Object value = parameters.get(key);
                    if (value != null && (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0)) throw new IllegalArgumentException("Invalid yaw policy");
                }
            }
        }
    }
    public record Order(String code, String label, Timing timing, List<Effect> orderEffects, List<Effect> tokenEffects) {
        public Order {
            if (code == null || code.isBlank() || label == null || label.isBlank() || timing == null) throw new IllegalArgumentException("Invalid order");
            orderEffects = orderEffects == null ? List.of() : List.copyOf(orderEffects);
            tokenEffects = tokenEffects == null ? List.of() : List.copyOf(tokenEffects);
            var policy = new java.util.HashMap<Kind, Map<String, Object>>();
            for (Effect effect : java.util.stream.Stream.concat(orderEffects.stream(), tokenEffects.stream()).toList()) {
                Timing expected = switch (effect.kind()) {
                    case CHANGE_SPEED, EXTRA_YAW -> Timing.BEFORE_MOVE;
                    case RESTORE, GRANT_ACTIVATIONS -> Timing.ON_REVEAL;
                    case ADD_ATTACK_DICE, REROLL_ATTACK_DICE -> Timing.BEFORE_ATTACK;
                };
                if (timing != expected) throw new IllegalArgumentException("Effect does not match order timing");
                if (policy.putIfAbsent(effect.kind(), effect.parameters()) != null && !policy.get(effect.kind()).equals(effect.parameters())) throw new IllegalArgumentException("Combined effects need matching policies");
            }
        }
    }
}
