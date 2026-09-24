package com.metis.engine.core.rules;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Typed, deterministic expression evaluation with explicit collection/instruction limits. */
public final class Expressions {
    private Expressions() { }
    public static RuleValue evaluate(Expression expression, EvaluationContext context) {
        context.budget().spend(expression.path());
        try {
            RuleValue value = switch (expression) {
                case Expression.Literal literal -> literal.value();
                case Expression.Variable variable -> required(context.variables().get(variable.name()), variable.path());
                case Expression.State state -> required(context.state().get(state.name()), state.path());
                case Expression.Entities entities -> RuleValue.list(RuleType.entity(entities.entityType()), context.entities().values().stream()
                        .filter(entity -> entity.type().equals(entities.entityType())).sorted(java.util.Comparator.comparing(EvaluationContext.Entity::id))
                        .map(entity -> RuleValue.entity(entity.type(), entity.id())).toList());
                case Expression.Field field -> field(field, context);
                case Expression.ListValue list -> RuleValue.list(list.type().element(), list.items().stream().map(item -> evaluate(item,context)).toList());
                case Expression.Select select -> select(select,context);
                case Expression.Apply apply -> apply(apply,context);
                case Expression.Query query -> query(query,context);
            };
            checkCollection(value,context,expression.path());
            return value.as(expression.type());
        } catch (RuleEvaluationException error) { throw error; }
        catch (RuleValue.BoundsException error) { throw new RuleEvaluationException("VALUE_BOUNDS",expression.path(),error.getMessage()); }
        catch (ArithmeticException error) { throw new RuleEvaluationException("NUMERIC_ERROR",expression.path(),error.getMessage()); }
        catch (IllegalArgumentException | ClassCastException error) { throw new RuleEvaluationException("VALUE_TYPE",expression.path(),error.getMessage()); }
    }
    private static void checkCollection(RuleValue value, EvaluationContext context, String path) {
        if (value.type().kind() != RuleType.Kind.LIST) return;
        if (value.items().size() > context.maxCollectionSize()) throw new RuleEvaluationException("COLLECTION_LIMIT",path,"Collection exceeds the declared limit");
        for (var item : value.items()) { context.budget().spend(path);checkCollection(item,context,path); }
    }
    private static RuleValue required(RuleValue value,String path) {
        if (value == null) throw new RuleEvaluationException("MISSING_VALUE",path,"Required evaluation value is absent");
        return value;
    }
    private static RuleValue query(Expression.Query query,EvaluationContext context) {
        var handler=context.queries().get(query.operation());
        if(handler==null)throw new RuleEvaluationException("QUERY_UNAVAILABLE",query.path(),"Pinned query handler is unavailable");
        var arguments=new java.util.TreeMap<String,RuleValue>();
        for(var name:new java.util.TreeSet<>(query.arguments().keySet()))arguments.put(name,evaluate(query.arguments().get(name),context).as(query.parameters().get(name)));
        return required(handler.evaluate(java.util.Collections.unmodifiableMap(arguments),context.entities()),query.path());
    }
    private static RuleValue field(Expression.Field field, EvaluationContext context) {
        var ref = (RuleValue.EntityRef)evaluate(field.entity(),context).value();
        var entity = context.entities().get(ref.id());
        if (entity == null || !entity.type().equals(ref.type())) throw new RuleEvaluationException("ENTITY_NOT_FOUND",field.path(),"Entity reference does not resolve");
        return switch (field.access()) {
            case ATTRIBUTE -> required(entity.attributes().get(field.name()), field.path());
            case RESOURCE -> {
                Long amount = entity.resources().get(field.name());
                if (amount == null) throw new RuleEvaluationException("MISSING_VALUE",field.path(),"Resource is absent");
                yield RuleValue.integer(amount);
            }
            case OWNER -> {
                if (entity.owner() == null) throw new RuleEvaluationException("MISSING_VALUE",field.path(),"Entity has no player owner");
                yield RuleValue.player(entity.owner());
            }
        };
    }
    private static RuleValue select(Expression.Select select, EvaluationContext context) {
        var source = evaluate(select.source(),context).items();
        var result = new ArrayList<RuleValue>();
        for (var item : source) {
            var value = evaluate(select.body(),context.bind(select.binding(),item));
            if (!select.filter()) result.add(value);
            else if (value.bool()) result.add(item);
        }
        return RuleValue.list(select.type().element(),result);
    }
    private static RuleValue apply(Expression.Apply apply, EvaluationContext context) {
        var expressions = apply.arguments();
        if (apply.operator().equals("and") || apply.operator().equals("or")) {
            boolean and = apply.operator().equals("and");
            for (var expression : expressions) if (evaluate(expression,context).bool() != and) return RuleValue.bool(!and);
            return RuleValue.bool(and);
        }
        List<RuleValue> args = expressions.stream().map(expression -> evaluate(expression,context)).toList();
        RuleValue first = args.getFirst();
        return switch (apply.operator()) {
            case "not" -> RuleValue.bool(!first.bool());
            case "id" -> RuleValue.text(first.value() instanceof RuleValue.EntityRef ref ? ref.id() : (String)first.value());
            case "concat" -> RuleValue.text(args.stream().map(value -> (String)value.value()).collect(java.util.stream.Collectors.joining()));
            case "eq", "ne" -> RuleValue.bool(equal(first,args.get(1)) == apply.operator().equals("eq"));
            case "lt" -> RuleValue.bool(first.number().compareTo(args.get(1).number()) < 0);
            case "lte" -> RuleValue.bool(first.number().compareTo(args.get(1).number()) <= 0);
            case "gt" -> RuleValue.bool(first.number().compareTo(args.get(1).number()) > 0);
            case "gte" -> RuleValue.bool(first.number().compareTo(args.get(1).number()) >= 0);
            case "add", "sub", "mul" -> arithmetic(apply.operator(),apply.type(),first,args.get(1));
            case "div" -> RuleValue.decimal(first.number().divide(args.get(1).number(),MathContext.DECIMAL128));
            case "floor", "ceil" -> RuleValue.integer(first.number().setScale(0,apply.operator().equals("floor")?RoundingMode.FLOOR:RoundingMode.CEILING).longValueExact());
            case "count" -> RuleValue.integer(first.items().size());
            case "sum" -> {
                RuleValue sum = apply.type().equals(RuleType.INTEGER) ? RuleValue.integer(0) : RuleValue.decimal(BigDecimal.ZERO);
                for (var item : first.items()) sum = arithmetic("add",apply.type(),sum,item);
                yield sum;
            }
            case "contains" -> RuleValue.bool(first.items().stream().anyMatch(item -> equal(item,args.get(1))));
            case "append" -> {
                var items=new ArrayList<>(first.items());items.add(args.get(1));yield RuleValue.list(first.type().element(),items);
            }
            case "at" -> {
                long index = args.get(1).integer();
                if (index < 0 || index >= first.items().size()) throw new RuleEvaluationException("INDEX_OUT_OF_BOUNDS",apply.path(),"List index is outside the collection");
                yield first.items().get((int)index);
            }
            default -> throw new RuleEvaluationException("OPERATOR_UNKNOWN",apply.path(),"Unknown compiled operator");
        };
    }
    private static boolean equal(RuleValue left,RuleValue right) {
        if (left.type().numeric() && right.type().numeric()) return left.number().compareTo(right.number()) == 0;
        if (left.type().accepts(right.type())) return left.equals(right.as(left.type()));
        return right.type().accepts(left.type()) && left.as(right.type()).equals(right);
    }
    private static RuleValue arithmetic(String op,RuleType type,RuleValue a,RuleValue b) {
        if (type.equals(RuleType.INTEGER)) return RuleValue.integer(switch(op) {
            case "add" -> Math.addExact(a.integer(),b.integer());
            case "sub" -> Math.subtractExact(a.integer(),b.integer());
            default -> Math.multiplyExact(a.integer(),b.integer());
        });
        return RuleValue.decimal(switch(op) {
            case "add" -> a.number().add(b.number(),MathContext.DECIMAL128);
            case "sub" -> a.number().subtract(b.number(),MathContext.DECIMAL128);
            default -> a.number().multiply(b.number(),MathContext.DECIMAL128);
        });
    }
}
