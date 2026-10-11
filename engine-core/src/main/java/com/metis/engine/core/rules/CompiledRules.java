package com.metis.engine.core.rules;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable compiler output, independent of JSON tree mutation and runtime services. */
public record CompiledRules(String id, String revision, String compilerVersion, String fingerprint,
                            String canonicalJson, Map<String,String> modules, Limits limits,
                            Map<String,Field> state, Map<String,EntityType> entities, Map<String,EventType> events,Map<String,Window> windows,
                            Map<String,Procedure> procedures, Map<String,Action> actions, Flow flow, String setup,MechanicsRules mechanics,SpatialRules space,ScenarioRules scenario) {
    public enum Audience { PUBLIC, OWNER, SERVER }
    public enum Ownership { PLAYER, NONE }
    public enum Controller { ALTERNATING_PLAYERS, ALTERNATING_ENTITIES, PLAYER_PHASE, SIMULTANEOUS, MANUAL, AUTOMATIC }
    public CompiledRules {
        modules=immutable(modules);state=immutable(state);entities=immutable(entities);events=immutable(events);windows=immutable(windows);
        procedures=immutable(procedures);actions=immutable(actions);
    }
    public record Limits(int maxInstructions,int maxCollectionSize,int maxCallDepth,int maxWindowDepth) {
        public Limits(int instructions,int collection,int calls) { this(instructions,collection,calls,8); }
    }
    public record Reaction(String id,Expression condition,String procedure,Map<String,Expression> arguments) {
        public Reaction { arguments=immutable(arguments); }
    }
    public record Window(Map<String,RuleType> parameters,Expression responders,int maxResponders,boolean allowPass,Audience visibility,List<Reaction> choices) {
        public Window { parameters=immutable(parameters);choices=List.copyOf(choices); }
    }
    public record Field(RuleType type,RuleValue initial,Audience visibility) { }
    public record Resource(long initial,long minimum,long maximum,Audience visibility) { }
    public record EntityType(Ownership ownership,Map<String,Field> attributes,Map<String,Resource> resources) {
        public EntityType { attributes=immutable(attributes);resources=immutable(resources); }
    }
    public record EventType(Audience visibility,Map<String,RuleType> fields) {
        public EventType { fields=immutable(fields); }
    }
    public record Instruction(String operation,Map<String,Expression> inputs,Map<String,String> references,
                              Map<String,List<Instruction>> blocks,String binding,String path) {
        public Instruction {
            inputs=immutable(inputs);references=immutable(references);
            var copied=new TreeMap<String,List<Instruction>>();blocks.forEach((key,value)->copied.put(key,List.copyOf(value)));
            blocks=immutable(copied);
        }
    }
    public record Procedure(Map<String,RuleType> parameters,List<Instruction> steps,long instructionBound) {
        public Procedure { parameters=immutable(parameters);steps=List.copyOf(steps); }
    }
    public record ManeuverControl(String entity,String speed,String yaw,String side,Expression chart) { }
    public record Action(Map<String,RuleType> parameters,Expression availableWhen,String procedure,Map<String,Expression> arguments,
                         Map<String,Expression> choices,int priority,boolean movementPreview,ManeuverControl maneuver) {
        public Action { parameters=immutable(parameters);arguments=immutable(arguments);choices=immutable(choices); }
    }
    public record Step(String id,List<String> actions,int minimum,int maximum) {
        public Step { actions=List.copyOf(actions); }
    }
    public record Phase(Controller controller,int groupSize,Expression entities,String entityParameter,List<Step> steps,String onEnter,String next) {
        public Phase { steps=List.copyOf(steps); }
    }
    public record Flow(String initial,String roundStart,Map<String,Phase> phases) {
        public Flow { phases=immutable(phases); }
    }
    private static <T> Map<String,T> immutable(Map<String,T> map) { return Collections.unmodifiableMap(new TreeMap<>(map)); }
}
