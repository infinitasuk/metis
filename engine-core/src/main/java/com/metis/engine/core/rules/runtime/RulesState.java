package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;

/** Authoritative checkpoint, never a player-facing DTO. Collections are deeply immutable. */
public record RulesState(String runtimeVersion,String definitionFingerprint,String sessionId,long version,
                         List<String> players,Map<String,RuleValue> globals,Map<String,EvaluationContext.Entity> entities,
                         Control control,List<Event> events) {
    public enum Status { SETUP, RUNNING, FINISHED }
    public enum Purpose { SETUP, ENTRY, ACTION }
    public enum FrameKind { BLOCK, LOOP, WINDOW }
    public RulesState {
        Objects.requireNonNull(runtimeVersion);Objects.requireNonNull(definitionFingerprint);Objects.requireNonNull(sessionId);Objects.requireNonNull(control);
        players=List.copyOf(players);globals=map(globals);entities=map(entities);events=List.copyOf(events);
        if(version<0)throw new IllegalArgumentException("Negative state version");
    }
    public record Progress(int step,Map<String,Integer> uses) {
        public Progress { uses=map(uses); }
        public static Progress empty() { return new Progress(0,Map.of()); }
    }
    public record Flow(String phase,int round,int playerIndex,String entity,int groupCompleted,
                       Map<String,Progress> progress,Set<String> completedPlayers,Set<String> completedEntities) {
        public Flow { progress=map(progress);completedPlayers=Collections.unmodifiableSet(new TreeSet<>(completedPlayers));completedEntities=Collections.unmodifiableSet(new TreeSet<>(completedEntities)); }
        public static Flow setup() { return new Flow(null,0,-1,null,0,Map.of(),Set.of(),Set.of()); }
    }
    /** BLOCK index is next instruction; LOOP index is next captured item. */
    public record Frame(FrameKind kind,String block,int index,Map<String,RuleValue> locals,
                        List<RuleValue> items,String binding,int callDepth) {
        public Frame { Objects.requireNonNull(kind);Objects.requireNonNull(block);locals=map(locals);items=List.copyOf(items); }
        Frame at(int next) { return new Frame(kind,block,next,locals,items,binding,callDepth); }
        Frame bind(String name,RuleValue value) { var copy=new TreeMap<>(locals);copy.put(name,value);return new Frame(kind,block,index,copy,items,binding,callDepth); }
    }
    public record Continuation(Purpose purpose,String actor,String action,int actionStep,List<Frame> frames,int remainingBudget) {
        public Continuation { Objects.requireNonNull(purpose);frames=List.copyOf(frames); }
    }
    public record Decision(String id,String player,String binding,CompiledRules.Audience visibility,List<RuleValue> options,String path) {
        public Decision { Objects.requireNonNull(id);Objects.requireNonNull(player);Objects.requireNonNull(binding);Objects.requireNonNull(visibility);options=List.copyOf(options); }
    }
    public record Control(Status status,Flow flow,Continuation continuation,Decision decision,
                           String outcome,List<String> winners,long nextDecision,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) MechanicsState mechanics) {
        public Control { Objects.requireNonNull(status);Objects.requireNonNull(flow);winners=List.copyOf(winners); }
        public Control(Status status,Flow flow,Continuation continuation,Decision decision,String outcome,List<String> winners,long nextDecision) {
            this(status,flow,continuation,decision,outcome,winners,nextDecision,null);
        }
    }
    public record Event(long sequence,String type,String actor,CompiledRules.Audience visibility,String recipient,
                        Map<String,RuleValue> values,String path) {
        public Event { Objects.requireNonNull(type);Objects.requireNonNull(visibility);values=map(values); }
    }
    public record Trace(String operation,String path,String actor) { }
    /** A committed delta, including the exact post-transition continuation, needs no rule re-execution. */
    public record Commit(String runtimeVersion,String definitionFingerprint,String sessionId,long beforeVersion,long version,
                         Map<String,RuleValue> globals,Map<String,EvaluationContext.Entity> entities,Set<String> removedEntities,
                         Control control,List<Event> events,List<Trace> trace) {
        public Commit { globals=map(globals);entities=map(entities);removedEntities=Collections.unmodifiableSet(new TreeSet<>(removedEntities));events=List.copyOf(events);trace=List.copyOf(trace); }
    }
    public record Result(boolean accepted,String code,String message,RulesState state,Commit commit) { }
    static <T> Map<String,T> map(Map<String,T> values) { return Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(values))); }
}
