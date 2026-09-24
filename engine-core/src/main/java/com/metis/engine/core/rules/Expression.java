package com.metis.engine.core.rules;

import java.util.List;
import java.util.Map;

public sealed interface Expression {
    RuleType type();
    String path();
    record Literal(RuleValue value, String path) implements Expression {
        public RuleType type() { return value.type(); }
    }
    record Variable(String name, RuleType type, String path) implements Expression { }
    record State(String name, RuleType type, String path) implements Expression { }
    record Entities(String entityType, String path) implements Expression {
        public RuleType type() { return RuleType.list(RuleType.entity(entityType)); }
    }
    enum Access { ATTRIBUTE, RESOURCE, OWNER }
    record Field(Expression entity, Access access, String name, RuleType type, String path) implements Expression { }
    record ListValue(RuleType type, List<Expression> items, String path) implements Expression {
        public ListValue { items = List.copyOf(items); }
    }
    record Apply(String operator, List<Expression> arguments, RuleType type, String path) implements Expression {
        public Apply { arguments = List.copyOf(arguments); }
    }
    record Select(Expression source, String binding, Expression body, boolean filter, RuleType type, String path) implements Expression { }
    record Query(String operation,Map<String,Expression> arguments,Map<String,RuleType> parameters,RuleType type,String path) implements Expression {
        public Query { arguments=Map.copyOf(arguments);parameters=Map.copyOf(parameters); }
    }
}
