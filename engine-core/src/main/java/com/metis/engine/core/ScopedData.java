package com.metis.engine.core;

import java.util.Map;

/** Persist the audience with the data, so replay retains the original disclosure. */
public final class ScopedData {
    private ScopedData() {}
    public static Map<String, Object> owner(long playerId, Object value) {
        return Map.of("$visibility", "OWNER", "ownerPlayerId", playerId, "value", value);
    }
    public static Map<String, Object> server(Object value) {
        return Map.of("$visibility", "SERVER", "value", value);
    }
    public static Object remapOwners(Object value, Map<Long,Long> players) {
        if (value instanceof java.util.List<?> list) return list.stream().map(item -> remapOwners(item, players)).toList();
        if (!(value instanceof Map<?,?> map)) return value;
        var result = new java.util.HashMap<String,Object>();
        map.forEach((key,item) -> result.put(key.toString(), remapOwners(item, players)));
        if ("OWNER".equals(map.get("$visibility")) && map.get("ownerPlayerId") instanceof Number owner && players.containsKey(owner.longValue())) {
            result.put("ownerPlayerId", players.get(owner.longValue()));
        }
        return result;
    }
}
