package com.metis.engine.core;

import java.util.HashSet;
import java.util.List;

/** Versioned sequencing configuration. Action effects remain separate rule modules. */
public record TurnStructure(boolean deploymentRequired, List<Phase> phases,
                            VisibilityRules visibility, List<ResponseRule> responses, OrderRules orders) {
    public TurnStructure(boolean deploymentRequired, List<Phase> phases, VisibilityRules visibility, List<ResponseRule> responses) {
        this(deploymentRequired, phases, visibility, responses, null);
    }
    public TurnStructure(boolean deploymentRequired, List<Phase> phases) {
        this(deploymentRequired, phases, VisibilityRules.defaults(), List.of());
    }
    public enum Mode { MANUAL, ALTERNATING_UNITS, PLAYER_PHASE, ALTERNATING_ACTIONS }

    public TurnStructure {
        if (phases == null || phases.isEmpty()) throw new IllegalArgumentException("At least one phase is required");
        phases = List.copyOf(phases);
        visibility = visibility == null ? VisibilityRules.defaults() : visibility;
        responses = responses == null ? List.of() : List.copyOf(responses);
        if (responses.stream().map(ResponseRule::action).distinct().count() != responses.size()) throw new IllegalArgumentException("Duplicate response trigger");
        var codes = new HashSet<String>();
        for (Phase phase : phases) {
            if (!codes.add(phase.code())) throw new IllegalArgumentException("Duplicate phase code");
        }
        if (orders != null) {
            var planning = phases.stream().filter(phase -> phase.code().equals(orders.planningPhase())).findFirst().orElseThrow(() -> new IllegalArgumentException("Order planning phase missing"));
            var activation = phases.stream().filter(phase -> phase.code().equals(orders.activationPhase())).findFirst().orElseThrow(() -> new IllegalArgumentException("Order activation phase missing"));
            if (planning.mode() != Mode.MANUAL || !List.of(Mode.ALTERNATING_UNITS, Mode.PLAYER_PHASE).contains(activation.mode())
                    || visibility.units() == VisibilityRules.Audience.SERVER) throw new IllegalArgumentException("Orders require owner-visible entities and compatible phases");
        }
    }

    public Phase phase(String code) {
        return phases.stream().filter(phase -> phase.code().equals(code)).findFirst().orElse(null);
    }

    public record Phase(String code, String label, Mode mode, List<String> classifications,
                        int groupSize, List<Step> steps, boolean refreshResourcesAtEnd, List<PrivateInput> privateInputs) {
        public Phase(String code, String label, Mode mode, List<String> classifications, int groupSize, List<Step> steps, boolean refreshResourcesAtEnd) {
            this(code, label, mode, classifications, groupSize, steps, refreshResourcesAtEnd, List.of());
        }
        public Phase {
            if (code == null || code.isBlank() || List.of("setup", "deployment", "victory").contains(code)
                    || label == null || label.isBlank() || mode == null || groupSize < 1) {
                throw new IllegalArgumentException("Invalid phase definition");
            }
            classifications = classifications == null ? List.of() : List.copyOf(classifications);
            steps = steps == null ? List.of() : List.copyOf(steps);
            privateInputs = privateInputs == null ? List.of() : List.copyOf(privateInputs);
            if (!privateInputs.isEmpty() && mode != Mode.MANUAL) throw new IllegalArgumentException("Private phase choices require a manual phase");
            if (privateInputs.stream().map(PrivateInput::code).distinct().count() != privateInputs.size()) throw new IllegalArgumentException("Duplicate private input");
            if (mode != Mode.MANUAL && steps.isEmpty()) throw new IllegalArgumentException("Active phases require steps");
            if (mode == Mode.MANUAL && !steps.isEmpty()) throw new IllegalArgumentException("Manual phases cannot define entity actions");
            if (mode == Mode.ALTERNATING_ACTIONS && (steps.size() != 1 || steps.getFirst().maximum() != 1)) {
                throw new IllegalArgumentException("Alternating actions require one single-action step");
            }
            var codes = new HashSet<String>();
            for (Step step : steps) if (!codes.add(step.code())) throw new IllegalArgumentException("Duplicate step code");
        }
    }

    public record PrivateInput(String code, String label, List<String> choices, boolean required, boolean revealAtEnd) {
        public PrivateInput {
            if (code == null || code.isBlank() || label == null || label.isBlank() || choices == null || choices.isEmpty()) throw new IllegalArgumentException("Invalid private input");
            choices = List.copyOf(choices);
        }
    }

    public record ResponseChoice(String code, String label, java.util.Map<String, Object> parameters) {
        public ResponseChoice {
            if (code == null || code.isBlank() || code.equals("decline") || code.startsWith("resource:") || label == null || label.isBlank()) throw new IllegalArgumentException("Invalid response choice");
            parameters = parameters == null ? java.util.Map.of() : java.util.Map.copyOf(parameters);
        }
    }

    public record ResponseRule(String action, String targetUnitParameter, String label,
                               java.util.Map<String, Object> declineParameters, List<ResponseChoice> choices,
                               String resourceField, String resourceParameter) {
        public ResponseRule {
            if (action == null || action.isBlank() || targetUnitParameter == null || targetUnitParameter.isBlank() || label == null || label.isBlank()) throw new IllegalArgumentException("Invalid response rule");
            declineParameters = declineParameters == null ? java.util.Map.of() : java.util.Map.copyOf(declineParameters);
            choices = choices == null ? List.of() : List.copyOf(choices);
            if (choices.stream().map(ResponseChoice::code).distinct().count() != choices.size()) throw new IllegalArgumentException("Duplicate response choice");
            if (resourceField != null && (!List.of("readyDefenseTokens", "shieldZones").contains(resourceField) || resourceParameter == null || resourceParameter.isBlank())) throw new IllegalArgumentException("Unsupported resource binding");
            var reserved = new java.util.HashSet<>(List.of("actorPlayerId", "unitId", "targetUnitId", targetUnitParameter));
            if (declineParameters.keySet().stream().anyMatch(reserved::contains)
                    || choices.stream().anyMatch(choice -> choice.parameters().keySet().stream().anyMatch(reserved::contains))
                    || (resourceParameter != null && reserved.contains(resourceParameter))) throw new IllegalArgumentException("Responses cannot replace action ownership or targets");
        }
    }

    public record Step(String code, String label, List<String> actions, int minimum, int maximum,
                       boolean editableMovement, String distinctParameter) {
        public Step {
            if (code == null || code.isBlank() || label == null || label.isBlank()
                    || actions == null || actions.isEmpty() || minimum < 0 || maximum < 1 || minimum > maximum) {
                throw new IllegalArgumentException("Invalid activation step");
            }
            actions = List.copyOf(actions);
            if (actions.stream().anyMatch(action -> action == null || action.isBlank())) {
                throw new IllegalArgumentException("Step action codes are required");
            }
            if (actions.stream().anyMatch(action -> List.of("start_game", "deploy_unit", "advance_phase", "activate_unit",
                    "end_activation", "end_turn", "advance_step", "reset_movement", "plan_orders", "resolve_order", "store_order", "discard_order",
                    "activate_support_unit", "end_support_activation", "end_order_grant").contains(action))) {
                throw new IllegalArgumentException("Sequencing controls cannot be used as step effects");
            }
            if (editableMovement && (!actions.contains("move_unit") || maximum != 1)) {
                throw new IllegalArgumentException("Editable movement requires a single-use move step");
            }
        }
    }
}
