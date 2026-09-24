package com.metis.engine.core.rules;

import java.util.HashMap;
import java.util.Map;

/** Pure evaluation input. No database, clock, random source or client identity lookup. */
public record EvaluationContext(Map<String, RuleValue> variables, Map<String, RuleValue> state,
                                Map<String, Entity> entities, int maxCollectionSize, Budget budget,Map<String,QueryHandler> queries) {
    @FunctionalInterface public interface QueryHandler {
        RuleValue evaluate(Map<String,RuleValue> arguments,Map<String,Entity> entities);
    }
    public EvaluationContext(Map<String,RuleValue> variables,Map<String,RuleValue> state,Map<String,Entity> entities,int maxCollectionSize,Budget budget) {
        this(variables,state,entities,maxCollectionSize,budget,Map.of());
    }
    public record Entity(String type, String id, String owner, Map<String, RuleValue> attributes, Map<String, Long> resources) {
        public Entity { attributes = Map.copyOf(attributes); resources = Map.copyOf(resources); }
    }
    public EvaluationContext {
        variables = Map.copyOf(variables); state = Map.copyOf(state); entities = Map.copyOf(entities);queries=Map.copyOf(queries);
        if (maxCollectionSize < 1 || budget == null) throw new IllegalArgumentException("Evaluation limits required");
        if (entities.size()>maxCollectionSize) throw new IllegalArgumentException("Entity collection exceeds evaluation limit");
        entities.forEach((id,entity) -> {
            if (!id.equals(entity.id())) throw new IllegalArgumentException("Entity map key differs from identity");
        });
    }
    public EvaluationContext bind(String name, RuleValue value) {
        var next = new HashMap<>(variables); next.put(name, value);
        return new EvaluationContext(next, state, entities, maxCollectionSize, budget,queries);
    }
    public static final class Budget {
        private int remaining;
        public Budget(int instructions) {
            if (instructions < 1) throw new IllegalArgumentException("Positive budget required");
            remaining = instructions;
        }
        public void spend(String path) {
            if (remaining == 0) throw new RuleEvaluationException("EVALUATION_LIMIT", path, "Expression evaluation budget exhausted");
            remaining--;
        }
        public int remaining() { return Math.max(0,remaining); }
    }
}
