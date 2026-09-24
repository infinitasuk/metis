package com.metis.engine.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Simultaneous, owner-visible choices. Command queues/effects are a separate module. */
public final class PrivatePlanning {
    private PrivatePlanning() {}
    public static String stage(GameState state) { return state.round() + ":" + state.phaseCode(); }
    private static Map<String, Object> object(Object value) {
        var result = new HashMap<String, Object>();
        if (value instanceof Map<?, ?> map) map.forEach((k,v) -> result.put(k.toString(), v));
        return result;
    }
    public static Map<String, Object> choices(GameState state, long player) {
        var stages = object(state.values().get("privateChoices"));
        var players = object(stages.get(stage(state)));
        var choices = object(players.get(String.valueOf(player)));
        return choices.containsKey("$visibility") ? object(choices.get("value")) : choices;
    }
    public static boolean ready(GameState state, TurnStructure.Phase phase) {
        return state.players().stream().allMatch(player -> phase.privateInputs().stream()
                .filter(TurnStructure.PrivateInput::required).allMatch(input -> choices(state, player.playerId()).containsKey(input.code())));
    }
    public static List<LegalAction> legal(GameState state, TurnStructure.Phase phase) {
        var result = new ArrayList<LegalAction>();
        for (Player player : state.players()) for (var input : phase.privateInputs()) {
            result.add(new LegalAction("submit_private_choice", player.playerId(), input.label(),
                    Map.of("inputCode", "string", "value", "string"),
                    Map.of("inputCode", input.code(), "label", input.label(), "choices", input.choices())));
        }
        if (ready(state, phase)) result.add(new LegalAction("advance_phase", 0, "Finish " + phase.label(), Map.of()));
        return result;
    }
    public static GameState submit(GameState state, long player, String input, String value) {
        var values = object(state.values());
        var stages = object(values.get("privateChoices"));
        var players = object(stages.get(stage(state)));
        var selected = choices(state, player); selected.put(input, value);
        players.put(String.valueOf(player), ScopedData.owner(player, Map.copyOf(selected)));
        stages.put(stage(state), Map.copyOf(players)); values.put("privateChoices", Map.copyOf(stages));
        return copy(state, values);
    }
    public static GameState reveal(GameState state, TurnStructure.Phase phase) {
        var revealed = new HashMap<String, Object>();
        for (Player player : state.players()) {
            var selected = choices(state, player.playerId()); var publicChoices = new HashMap<String, Object>();
            for (var input : phase.privateInputs()) if (input.revealAtEnd() && selected.containsKey(input.code())) publicChoices.put(input.code(), selected.get(input.code()));
            if (!publicChoices.isEmpty()) revealed.put(String.valueOf(player.playerId()), Map.copyOf(publicChoices));
        }
        var values = object(state.values()); var publicStages = object(values.get("publicChoices"));
        if (!revealed.isEmpty()) publicStages.put(stage(state), Map.copyOf(revealed));
        values.put("publicChoices", Map.copyOf(publicStages)); return copy(state, values);
    }
    private static GameState copy(GameState s, Map<String,Object> values) {
        return new GameState(s.sessionId(),s.definitionVersionId(),s.status(),s.round(),s.phaseCode(),s.activePlayerId(),s.stateVersion(),s.players(),s.units(),s.board(),s.events(),values);
    }
}
