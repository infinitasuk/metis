package com.metis.engine.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Serializable sequencing state, carried in the extensible runtime values map. */
public record ActivationContext(Long unitId, int stepIndex, Map<String, Integer> uses,
                                Map<String, List<String>> distinctValues, Set<Long> completedUnits,
                                Set<Long> completedPlayers, int groupCompleted) {
    public static final String KEY = "activation";
    public ActivationContext {
        uses = Map.copyOf(uses);
        var copied = new HashMap<String, List<String>>();
        distinctValues.forEach((key, value) -> copied.put(key, List.copyOf(value)));
        distinctValues = Map.copyOf(copied);
        completedUnits = Set.copyOf(completedUnits);
        completedPlayers = Set.copyOf(completedPlayers);
    }
    public static ActivationContext empty() {
        return new ActivationContext(null, 0, Map.of(), Map.of(), Set.of(), Set.of(), 0);
    }
    public Map<String, Object> toMap() {
        var result = new HashMap<String, Object>();
        if (unitId != null) result.put("unitId", unitId);
        result.put("stepIndex", stepIndex);
        result.put("uses", uses);
        result.put("distinctValues", distinctValues);
        result.put("completedUnits", completedUnits.stream().sorted().toList());
        result.put("completedPlayers", completedPlayers.stream().sorted().toList());
        result.put("groupCompleted", groupCompleted);
        return Map.copyOf(result);
    }
    public static ActivationContext from(GameState state) {
        if (!(state.values().get(KEY) instanceof Map<?, ?> raw)) return empty();
        var uses = new HashMap<String, Integer>();
        if (raw.get("uses") instanceof Map<?, ?> values) values.forEach((k, v) -> uses.put(k.toString(), ((Number) v).intValue()));
        var distinct = new HashMap<String, List<String>>();
        if (raw.get("distinctValues") instanceof Map<?, ?> values) values.forEach((k, v) ->
                distinct.put(k.toString(), ((List<?>) v).stream().map(Object::toString).toList()));
        return new ActivationContext(raw.get("unitId") instanceof Number id ? id.longValue() : null,
                raw.get("stepIndex") instanceof Number step ? step.intValue() : 0, uses, distinct,
                ids(raw.get("completedUnits")), ids(raw.get("completedPlayers")),
                raw.get("groupCompleted") instanceof Number group ? group.intValue() : 0);
    }
    private static Set<Long> ids(Object value) {
        return value instanceof List<?> ids ? ids.stream().map(id -> ((Number) id).longValue()).collect(Collectors.toSet()) : Set.of();
    }
}
