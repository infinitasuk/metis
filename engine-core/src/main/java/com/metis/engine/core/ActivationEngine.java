package com.metis.engine.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static com.metis.engine.core.TurnStructure.Mode.*;

/** Game-neutral sequencing around a supplied action-effect implementation. */
public final class ActivationEngine {
    private final GameDefinition definition;
    private final BiFunction<GameState, Action, ActionResult> effects;

    public ActivationEngine(GameDefinition definition, BiFunction<GameState, Action, ActionResult> effects) {
        this.definition = definition;
        this.effects = effects;
    }

    public boolean eligible(TurnStructure.Phase phase, UnitState unit) {
        if (unit.hullPoints() <= 0) return false;
        if (phase.classifications().isEmpty()) return true;
        return definition.unitDefinitions().stream().anyMatch(profile -> profile.code().equals(unit.unitCode())
                && phase.classifications().stream().anyMatch(value -> value.equalsIgnoreCase(profile.classification())));
    }

    public List<LegalAction> legalActions(GameState state) {
        if (!"running".equals(state.status())) return List.of();
        if ("deployment".equals(state.phaseCode())) return List.of(new LegalAction("advance_phase", 0, "Finish deployment", Map.of()));
        var phase = definition.turnStructure().phase(state.phaseCode());
        if (phase == null) return List.of();
        var orders = new OrderEngine(definition);
        if (phase.mode() == MANUAL) {
            var actions = new ArrayList<>(PrivatePlanning.legal(state, phase));
            if (!orders.ready(state)) actions.removeIf(action -> action.type().equals("advance_phase"));
            actions.addAll(orders.planningActions(state)); return actions;
        }
        if (!OrderEngine.grant(state).isEmpty()) return orders.supportActions(state);
        var context = ActivationContext.from(state);
        var actions = new ArrayList<LegalAction>();
        if (phase.mode() == ALTERNATING_ACTIONS) {
            for (String type : phase.steps().getFirst().actions()) actions.add(new LegalAction(type, state.activePlayerId(), type, Map.of()));
            return actions;
        }
        for (UnitState unit : state.units()) {
            if (unit.playerId() != state.activePlayerId() || !eligible(phase, unit)
                    || OrderEngine.spentActivation(state, unit.unitId())
                    || context.completedUnits().contains(unit.unitId())
                    || (context.unitId() != null && context.unitId() != unit.unitId())) continue;
            if (context.unitId() == null) actions.add(new LegalAction("activate_unit", unit.playerId(), "Activate unit " + unit.unitId(), Map.of("unitId", "number"), Map.of("unitId", unit.unitId())));
            if (context.unitId() == null && orders.activation(state, unit)) continue;
            if (context.unitId() != null) actions.addAll(orders.actions(state, unit));
            for (String type : phase.steps().stream().flatMap(step -> step.actions().stream()).distinct().toList()) {
                int index = stepFor(phase, context, type, resetAllowed(state, context));
                if (index >= 0) actions.add(new LegalAction(type, unit.playerId(),
                        type + " for unit " + unit.unitId(), Map.of("unitId", "number"),
                        Map.of("unitId", unit.unitId(), "stepCode", phase.steps().get(index).code())));
            }
            if (context.unitId() != null && resetAllowed(state, context)) actions.add(new LegalAction("reset_movement", unit.playerId(),
                    "Reset movement for unit " + unit.unitId(), Map.of("unitId", "number"), Map.of("unitId", unit.unitId())));
        }
        if (context.unitId() != null && minimumsMet(phase, context)) actions.add(new LegalAction("end_activation", state.activePlayerId(), "Finish activation", Map.of()));
        if (context.unitId() != null && canAdvanceStep(phase, context)) actions.add(new LegalAction("advance_step", state.activePlayerId(), "Continue to next activation step", Map.of()));
        return actions;
    }

    public ActionResult apply(GameState state, Action action) {
        if ("start_game".equals(action.type())) {
            if (!"setup".equals(state.status())) return reject("GAME_ALREADY_STARTED", "The game is not in setup");
            GameState next = definition.turnStructure().deploymentRequired()
                    ? copy(state, "running", 1, "deployment", null, state.units(), ActivationContext.empty())
                    : enter(state, 0, 1);
            return event(state, next, "game_started", action.actorPlayerId(), Map.of());
        }
        if (!"running".equals(state.status())) return reject("GAME_NOT_RUNNING", "The game is not running");
        if ("deployment".equals(state.phaseCode())) {
            if ("deploy_unit".equals(action.type())) return effects.apply(state, action);
            if ("advance_phase".equals(action.type()) && !state.units().isEmpty()) {
                return event(state, enter(state, 0, state.round()), "phase_changed", action.actorPlayerId(), Map.of());
            }
            return reject("DEPLOYMENT_INCOMPLETE", "Deploy units before continuing");
        }
        var phase = definition.turnStructure().phase(state.phaseCode());
        if (phase == null) return reject("PHASE_UNKNOWN", "The current phase is not defined");
        var orders = new OrderEngine(definition);
        if (phase.mode() == MANUAL) {
            if ("plan_orders".equals(action.type())) return orders.plan(state, action);
            if ("submit_private_choice".equals(action.type())) {
                if (state.players().stream().noneMatch(player -> player.playerId() == action.actorPlayerId())) return reject("PLAYER_NOT_FOUND", "Unknown player");
                var input = phase.privateInputs().stream().filter(item -> item.code().equals(action.parameters().get("inputCode"))).findFirst().orElse(null);
                if (input == null || !(action.parameters().get("value") instanceof String value) || !input.choices().contains(value)) return reject("PRIVATE_CHOICE_INVALID", "Choose an available value for this input");
                return event(state, PrivatePlanning.submit(state, action.actorPlayerId(), input.code(), value), "private_choice_submitted", action.actorPlayerId(),
                        ScopedData.owner(action.actorPlayerId(), Map.of("inputCode", input.code(), "value", value)));
            }
            if (!PrivatePlanning.ready(state, phase)) return reject("PLANNING_INCOMPLETE", "Waiting for required player choices");
            if (!orders.ready(state)) return reject("ORDER_PLANNING_INCOMPLETE", "Fill every eligible unit's order queue before continuing");
            return "advance_phase".equals(action.type())
                    ? event(state, nextPhase(orders.commit(PrivatePlanning.reveal(state, phase)), phase), "phase_changed", action.actorPlayerId(), Map.of())
                    : reject("ACTION_NOT_ALLOWED", "Finish the current phase before acting");
        }
        if (!ActionAuthorization.isActivePlayer(state, action.actorPlayerId())) return reject("NOT_ACTIVE_PLAYER", "It is not the acting player's turn");
        if (!OrderEngine.grant(state).isEmpty()) return orders.support(state, action, effects);
        if (List.of("resolve_order", "store_order", "discard_order").contains(action.type())) return orders.resolve(state, action);
        var context = ActivationContext.from(state);
        if ("advance_step".equals(action.type())) {
            if (context.unitId() == null || !canAdvanceStep(phase, context)) return reject("STEP_INCOMPLETE", "Complete this step's required actions first");
            var next = new ActivationContext(context.unitId(), context.stepIndex() + 1, context.uses(), context.distinctValues(),
                    context.completedUnits(), context.completedPlayers(), context.groupCompleted());
            var advanced = OrderEngine.afterAction(state, new Action("advance_step", action.actorPlayerId(), Map.of("unitId", context.unitId())));
            return event(state, copy(advanced, state.status(), state.round(), state.phaseCode(), state.activePlayerId(), state.units(), next),
                    "activation_step_changed", action.actorPlayerId(), Map.of("unitId", context.unitId()));
        }
        if (List.of("end_activation", "end_turn").contains(action.type())) {
            if (context.unitId() == null || !minimumsMet(phase, context)) return reject("ACTIVATION_INCOMPLETE", "Complete the active unit's required actions first");
            long completedId = context.unitId();
            var units = state.units().stream().map(unit -> unit.unitId() == completedId ? status(unit, "activated") : unit).toList();
            GameState next = finish(copy(OrderEngine.close(state, completedId), state.status(), state.round(), state.phaseCode(), state.activePlayerId(), units, context), phase, context);
            return event(state, next, "activation_completed", action.actorPlayerId(), Map.of("unitId", context.unitId()));
        }
        if (phase.mode() != ALTERNATING_ACTIONS) {
            if (!(action.parameters().get("unitId") instanceof Number id)) return reject("UNIT_REQUIRED", "Select a unit");
            UnitState unit = state.units().stream().filter(item -> item.unitId() == id.longValue()).findFirst().orElse(null);
            if (unit == null || unit.playerId() != action.actorPlayerId()) return reject("UNIT_NOT_OWNED", "Select a unit controlled by the active player");
            if (!eligible(phase, unit) || OrderEngine.spentActivation(state, unit.unitId()) || context.completedUnits().contains(unit.unitId())) return reject("UNIT_UNAVAILABLE", "This unit cannot activate in the current phase");
            if (context.unitId() != null && context.unitId() != unit.unitId()) return reject("ACTIVATION_IN_PROGRESS", "Finish the active unit before selecting another");
            if (context.unitId() == null && orders.activation(state, unit) && !action.type().equals("activate_unit")) return reject("ORDER_REVEAL_REQUIRED", "Activate this unit to reveal its next order first");
            context = new ActivationContext(unit.unitId(), context.stepIndex(), context.uses(), context.distinctValues(),
                    context.completedUnits(), context.completedPlayers(), context.groupCompleted());
            if ("activate_unit".equals(action.type())) {
                if (ActivationContext.from(state).unitId() != null) return reject("ACTIVATION_IN_PROGRESS", "This unit is already active");
                try {
                    var revealed = orders.reveal(state, unit);
                    return event(state, copy(revealed, state.status(), state.round(), state.phaseCode(), state.activePlayerId(), state.units(), context),
                            "unit_activated", action.actorPlayerId(), Map.of("unitId", unit.unitId()));
                } catch (IllegalArgumentException error) { return reject("ORDER_QUEUE_EMPTY", error.getMessage()); }
            }
        }
        boolean reset = "reset_movement".equals(action.type());
        int index = reset ? context.stepIndex() : stepFor(phase, context, action.type(), resetAllowed(state, context));
        if (index < 0 || index >= phase.steps().size() || (reset && !resetAllowed(state, context))) return reject("ACTION_BUDGET_EXCEEDED", "This action is unavailable in the current activation step");
        var step = phase.steps().get(index);
        String key = step.distinctParameter() == null ? null : String.valueOf(action.parameters().get(step.distinctParameter()));
        if (key != null && !action.parameters().containsKey(step.distinctParameter())) {
            return reject("ACTION_SOURCE_REQUIRED", "Choose an action source");
        }
        if (key != null && context.distinctValues().getOrDefault(step.code(), List.of()).contains(key)) {
            return reject("ACTION_SOURCE_ALREADY_USED", "Choose a different action source for this activation");
        }
        ActionResult effect = effects.apply(state, action);
        if (!effect.accepted()) return effect;
        if ("RESPONSE_REQUIRED".equals(effect.code())) return effect;
        var uses = new HashMap<>(context.uses());
        uses.put(step.code(), reset ? 0 : Math.min(step.maximum(), uses.getOrDefault(step.code(), 0) + 1));
        var keys = new HashMap<>(context.distinctValues());
        if (key != null) { var entries = new ArrayList<>(keys.getOrDefault(step.code(), List.of())); entries.add(key); keys.put(step.code(), entries); }
        context = new ActivationContext(context.unitId(), index, uses, keys, context.completedUnits(), context.completedPlayers(), context.groupCompleted());
        var effectState = OrderEngine.afterAction(effect.resultingState(), action);
        Long actingUnit = context.unitId();
        var units = effectState.units().stream().map(unit -> actingUnit != null && unit.unitId() == actingUnit && unit.hullPoints() > 0
                ? status(unit, "activating") : unit).toList();
        GameState next = copy(effectState, effectState.status(), effectState.round(), effectState.phaseCode(), effectState.activePlayerId(), units, context);
        if ("running".equals(next.status()) && phase.mode() == ALTERNATING_ACTIONS && uses.getOrDefault(step.code(), 0) >= step.maximum()) next = finish(next, phase, context);
        // Keep exactly one state-version/event increment for an atomic submitted action.
        var last = effectState.events().getLast();
        return event(state, next, last.type(), action.actorPlayerId(), last.payload());
    }

    private int stepFor(TurnStructure.Phase phase, ActivationContext context, String type, boolean editingMovement) {
        for (int i = context.stepIndex(); i < phase.steps().size(); i++) {
            var step = phase.steps().get(i);
            int used = context.uses().getOrDefault(step.code(), 0);
            if (step.actions().contains(type)) {
                if (used < step.maximum() || (editingMovement && step.editableMovement() && "move_unit".equals(type) && used == 1)) return i;
                return -1;
            }
            if (used < step.minimum()) return -1;
        }
        return -1;
    }

    private boolean minimumsMet(TurnStructure.Phase phase, ActivationContext context) {
        return phase.steps().stream().allMatch(step -> context.uses().getOrDefault(step.code(), 0) >= step.minimum());
    }

    private boolean canAdvanceStep(TurnStructure.Phase phase, ActivationContext context) {
        if (context.stepIndex() + 1 >= phase.steps().size()) return false;
        var step = phase.steps().get(context.stepIndex());
        return context.uses().getOrDefault(step.code(), 0) >= step.minimum();
    }

    private boolean resetAllowed(GameState state, ActivationContext context) {
        if (context.unitId() == null) return false;
        var phase = definition.turnStructure().phase(state.phaseCode());
        var step = phase.steps().get(context.stepIndex());
        if (!step.editableMovement() || context.uses().getOrDefault(step.code(), 0) == 0) return false;
        return state.events().stream().filter(event -> event.payload().get("unitId") instanceof Number id && id.longValue() == context.unitId())
                .reduce((a, b) -> b).map(event -> "unit_moved".equals(event.type())).orElse(false);
    }

    private GameState finish(GameState state, TurnStructure.Phase phase, ActivationContext context) {
        var completed = new HashSet<>(context.completedUnits());
        if (context.unitId() != null) completed.add(context.unitId());
        var playersDone = new HashSet<>(context.completedPlayers());
        int count = context.groupCompleted() + 1;
        var next = new ActivationContext(null, 0, Map.of(), Map.of(), completed, playersDone, count);
        long actor = state.activePlayerId();
        if (phase.mode() == ALTERNATING_ACTIONS) playersDone.add(actor);
        boolean ownRemaining = hasUnits(state, phase, next, actor);
        if (phase.mode() == PLAYER_PHASE && ownRemaining) return copy(state, state.status(), state.round(), state.phaseCode(), actor, state.units(), next);
        if (phase.mode() == ALTERNATING_UNITS && ownRemaining && count < phase.groupSize()) return copy(state, state.status(), state.round(), state.phaseCode(), actor, state.units(), next);
        next = new ActivationContext(null, 0, Map.of(), Map.of(), completed, playersDone, 0);
        var players = state.players().stream().sorted(java.util.Comparator.comparingInt(Player::seatNumber)).toList();
        int current = java.util.stream.IntStream.range(0, players.size()).filter(i -> players.get(i).playerId() == actor).findFirst().orElseThrow();
        for (int offset = 1; offset <= players.size(); offset++) {
            long id = players.get((current + offset) % players.size()).playerId();
            if (phase.mode() == ALTERNATING_ACTIONS ? !playersDone.contains(id) : hasUnits(state, phase, next, id)) {
                return copy(state, state.status(), state.round(), state.phaseCode(), id, state.units(), next);
            }
        }
        return nextPhase(state, phase);
    }

    private boolean hasUnits(GameState state, TurnStructure.Phase phase, ActivationContext context, long player) {
        return state.units().stream().anyMatch(unit -> unit.playerId() == player && eligible(phase, unit) && !OrderEngine.spentActivation(state, unit.unitId()) && !context.completedUnits().contains(unit.unitId()));
    }

    private GameState nextPhase(GameState state, TurnStructure.Phase phase) {
        if (phase.refreshResourcesAtEnd()) {
            var units = state.units().stream().map(unit -> {
                if (unit.hullPoints() <= 0) return unit;
                var tokens = new HashMap<>(unit.readyDefenseTokens());
                unit.exhaustedDefenseTokens().forEach((key, value) -> tokens.merge(key, value, Integer::sum));
                return new UnitState(unit.unitId(), unit.playerId(), unit.unitCode(), unit.positionX(), unit.positionY(), unit.facing(),
                        "ready", unit.hullPoints(), unit.shieldPoints(), tokens.values().stream().mapToInt(Integer::intValue).sum(),
                        unit.currentSpeed(), unit.shieldZones(), tokens, Map.of());
            }).toList();
            state = copy(state, state.status(), state.round(), state.phaseCode(), state.activePlayerId(), units, ActivationContext.empty());
        }
        int index = definition.turnStructure().phases().indexOf(phase) + 1;
        int round = state.round();
        if (index == definition.turnStructure().phases().size()) {
            index = 0; round++;
            int limit = state.values().get("maximumRounds") instanceof Number n ? n.intValue() : 6;
            if (!Boolean.TRUE.equals(state.values().get("unlimitedRounds")) && round > limit) {
                return copy(state, "finished", state.round(), "victory", null, state.units(), ActivationContext.empty());
            }
        }
        return enter(state, index, round);
    }

    private GameState enter(GameState state, int index, int round) {
        var phases = definition.turnStructure().phases();
        // Empty entity phases are skipped; stop after one cycle instead of recursing forever.
        for (int attempts = 0; attempts < phases.size(); attempts++) {
            var phase = phases.get(index);
            var entering = copy(state, state.status(), round, state.phaseCode(), state.activePlayerId(), state.units(), ActivationContext.empty());
            Long first = phase.mode() == MANUAL ? null : state.players().stream()
                    .sorted(java.util.Comparator.comparingInt(Player::seatNumber))
                    .filter(player -> phase.mode() == ALTERNATING_ACTIONS || hasUnits(entering, phase, ActivationContext.empty(), player.playerId()))
                    .map(Player::playerId).findFirst().orElse(null);
            if (phase.mode() == MANUAL || first != null) {
                var units = state.units().stream().map(unit -> eligible(phase, unit) && !OrderEngine.spentActivation(entering, unit.unitId()) ? status(unit, "ready") : unit).toList();
                return copy(state, "running", round, phase.code(), first, units, ActivationContext.empty());
            }
            index = (index + 1) % phases.size();
            if (index == 0) round++;
        }
        return copy(state, "finished", round, "victory", null, state.units(), ActivationContext.empty());
    }

    private static UnitState status(UnitState unit, String status) {
        return new UnitState(unit.unitId(), unit.playerId(), unit.unitCode(), unit.positionX(), unit.positionY(), unit.facing(), status,
                unit.hullPoints(), unit.shieldPoints(), unit.defenseTokens(), unit.currentSpeed(), unit.shieldZones(), unit.readyDefenseTokens(), unit.exhaustedDefenseTokens());
    }
    private static GameState copy(GameState state, String status, int round, String phase, Long player, List<UnitState> units, ActivationContext context) {
        var values = new HashMap<>(state.values()); values.put(ActivationContext.KEY, context.toMap());
        return new GameState(state.sessionId(), state.definitionVersionId(), status, round, phase, player, state.stateVersion(), state.players(), units, state.board(), state.events(), values);
    }
    static ActionResult event(GameState before, GameState after, String type, Long actor, Map<String, Object> data) {
        var payload = new HashMap<>(data);
        payload.put("phase", after.phaseCode()); payload.put("round", after.round());
        payload.put("activePlayerId", after.activePlayerId() == null ? 0L : after.activePlayerId());
        payload.put("activation", ActivationContext.from(after).toMap());
        payload.put("status", after.status());
        payload.put("unitChanges", after.units().stream().filter(unit -> !before.units().contains(unit)).toList());
        payload.put("orderState", after.values().getOrDefault("orderState", Map.of()));
        payload.put("orderActivations", after.values().getOrDefault("orderActivations", Map.of()));
        var events = new ArrayList<>(before.events());
        events.add(new GameEvent(events.size() + 1L, type, actor == null || actor == 0 ? null : actor, payload));
        return new ActionResult(true, "ACTION_ACCEPTED", type, new GameState(after.sessionId(), after.definitionVersionId(), after.status(), after.round(),
                after.phaseCode(), after.activePlayerId(), before.stateVersion() + 1, after.players(), after.units(), after.board(), events, after.values()));
    }
    private static ActionResult reject(String code, String message) { return new ActionResult(false, code, message, null); }
}
