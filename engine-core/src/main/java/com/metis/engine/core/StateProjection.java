package com.metis.engine.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure player/spectator projection. Unknown state fields and event types are private. */
public final class StateProjection {
    private static final Object OMIT = new Object();
    private static final Set<String> PUBLIC_VALUES = Set.of("activation", "responseWindow", "privateChoices", "publicChoices",
            "maximumRounds", "unlimitedRounds", "distancePerSpeed", "maximumMovementDistance", "shortRange", "mediumRange", "longRange", "outcome", "winnerPlayerId",
            "orderQueues", "orderState", "orderActivations");
    private static final Set<String> PUBLIC_EVENTS = Set.of("game_started", "game_paused", "phase_changed", "round_started", "round_limit_reached",
            "turn_started", "unit_deployed", "unit_moved", "unit_movement_reset", "unit_attacked", "game_won", "unit_activated",
            "activation_completed", "activation_step_changed", "response_opened", "order_resolved", "order_stored", "order_discarded", "support_activated", "support_completed", "order_grant_ended");
    private final VisibilityRules rules;
    private final Set<Long> owned;
    private final Map<Long, UnitState> units;

    public StateProjection(VisibilityRules rules, Set<Long> owned, List<UnitState> units) {
        this.rules = rules == null ? VisibilityRules.defaults() : rules;
        this.owned = Set.copyOf(owned);
        this.units = new HashMap<>(); units.forEach(unit -> this.units.put(unit.unitId(), unit));
    }
    public boolean canSeePlayerUnits(long player) {
        return rules.units() == VisibilityRules.Audience.PUBLIC || (rules.units() == VisibilityRules.Audience.OWNER && owned.contains(player));
    }
    private boolean canSeeUnit(long id) {
        return rules.units() == VisibilityRules.Audience.PUBLIC || (units.containsKey(id) && canSeePlayerUnits(units.get(id).playerId()));
    }
    public GameState state(GameState source) {
        var values = new HashMap<String, Object>();
        var permitted = new HashSet<>(PUBLIC_VALUES); permitted.addAll(rules.publicValues());
        source.values().forEach((key, value) -> {
            if (!permitted.contains(key) || key.equals("serverOnly") || key.equals("initialSetupOptions")) return;
            Object visible = project(value);
            if (visible != OMIT && visible != null) values.put(key, visible);
        });
        return new GameState(source.sessionId(), source.definitionVersionId(), source.status(), source.round(), source.phaseCode(),
                source.activePlayerId(), source.stateVersion(), source.players(), source.units().stream().filter(unit -> canSeePlayerUnits(unit.playerId())).toList(),
                source.board(), source.events().stream().map(this::event).toList(), values);
    }
    public GameEvent event(GameEvent source) {
        boolean explicit = source.payload().containsKey("$visibility");
        if (!explicit && !PUBLIC_EVENTS.contains(source.type()) && !rules.publicEvents().contains(source.type())) return hidden(source);
        Object visible = project(source.payload());
        if (!(visible instanceof Map<?, ?> map)) return hidden(source);
        var payload = new HashMap<String, Object>(); map.forEach((key, value) -> payload.put(key.toString(), value));
        return new GameEvent(source.sequence(), source.type(), source.actorPlayerId(), payload);
    }
    private GameEvent hidden(GameEvent event) {
        // Keep public sequencing and the viewer's own deltas usable for replay,
        // without exposing the hidden subject, pose, choice, or actor payload.
        if (!(event.payload().get("activation") instanceof Map<?, ?>)
                || !(event.payload().get("phase") instanceof String) || !(event.payload().get("round") instanceof Number)) {
            return new GameEvent(event.sequence(), "hidden_event", null, Map.of());
        }
        var metadata = new HashMap<String,Object>();
        for (String key : List.of("phase", "round", "activePlayerId", "status", "activation", "unitChanges", "orderState", "orderActivations")) {
            if (!event.payload().containsKey(key)) continue;
            Object visible = project(event.payload().get(key));
            if (visible != OMIT && visible != null) metadata.put(key, visible);
        }
        if (event.payload().containsKey("activation")) metadata.putIfAbsent("activation", Map.of());
        return new GameEvent(event.sequence(), "hidden_event", null, metadata);
    }
    private Object project(Object value) {
        if (value instanceof UnitState unit) return canSeePlayerUnits(unit.playerId()) ? unit : OMIT;
        if (value instanceof List<?> list) {
            var result = new ArrayList<>();
            for (Object item : list) { Object visible = project(item); if (visible != OMIT) result.add(visible); }
            return result;
        }
        if (!(value instanceof Map<?, ?> map)) return value;
        if (map.containsKey("$visibility")) {
            String scope = String.valueOf(map.get("$visibility"));
            boolean allowed = scope.equals("PUBLIC") || (scope.equals("OWNER") && map.get("ownerPlayerId") instanceof Number id && owned.contains(id.longValue()));
            return allowed ? project(map.get("value")) : OMIT;
        }
        // Handles both in-memory UnitState changes and JSON-deserialized changes.
        if (map.get("playerId") instanceof Number owner && map.containsKey("unitCode") && !canSeePlayerUnits(owner.longValue())) return OMIT;
        for (String ref : List.of("unitId", "targetUnitId")) {
            if (map.get(ref) instanceof Number id && !canSeeUnit(id.longValue())) return OMIT;
        }
        var result = new HashMap<String, Object>();
        map.forEach((key, item) -> {
            if (key.equals("serverOnly") || key.equals("initialSetupOptions")) return;
            if (key.equals("completedUnits") && item instanceof List<?> ids) {
                result.put(key.toString(), ids.stream().filter(id -> id instanceof Number n && canSeeUnit(n.longValue())).toList());
                return;
            }
            Object visible = project(item);
            if (visible != OMIT && visible != null) result.put(key.toString(), visible);
        });
        return result;
    }
}
