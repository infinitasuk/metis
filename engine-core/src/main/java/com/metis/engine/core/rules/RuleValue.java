package com.metis.engine.core.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable, non-null runtime value; integer and decimal remain distinct types. */
public record RuleValue(RuleType type, Object value) {
    static final class BoundsException extends IllegalArgumentException {
        BoundsException(String message) { super(message); }
    }
    public record EntityRef(String type, String id) {
        public EntityRef { Objects.requireNonNull(type); Objects.requireNonNull(id); }
    }
    public RuleValue {
        Objects.requireNonNull(type); Objects.requireNonNull(value);
        boolean valid = switch (type.kind()) {
            case INTEGER -> value instanceof Long;
            case DECIMAL -> value instanceof BigDecimal;
            case BOOLEAN -> value instanceof Boolean;
            case STRING, PLAYER -> value instanceof String;
            case ENTITY -> value instanceof EntityRef ref && (type.entityType().equals("*") || type.entityType().equals(ref.type()));
            case LIST -> value instanceof List<?> list && list.stream().allMatch(item -> item instanceof RuleValue v && type.element().accepts(v.type()));
        };
        if (!valid) throw new IllegalArgumentException("Value does not match " + type.display());
        if (value instanceof String text && text.length()>4096) throw new BoundsException("String exceeds 4096 characters");
        if (value instanceof BigDecimal number) {
            value = number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
            BigDecimal normalized = (BigDecimal) value;
            if (normalized.precision() > 34 || Math.abs((long)normalized.scale()) > 128) throw new BoundsException("Decimal exceeds precision/scale limits");
        }
        if (type.kind() == RuleType.Kind.LIST) {
            value = ((List<?>)value).stream().map(item -> ((RuleValue)item).as(type.element())).toList();
        }
    }
    public static RuleValue integer(long n) { return new RuleValue(RuleType.INTEGER, n); }
    public static RuleValue decimal(BigDecimal n) { return new RuleValue(RuleType.DECIMAL, n); }
    public static RuleValue bool(boolean value) { return new RuleValue(RuleType.BOOLEAN, value); }
    public static RuleValue text(String value) { return new RuleValue(RuleType.STRING, value); }
    public static RuleValue player(String value) { return new RuleValue(RuleType.PLAYER, value); }
    public static RuleValue entity(String type, String id) { return new RuleValue(RuleType.entity(type), new EntityRef(type,id)); }
    public static RuleValue list(RuleType element, List<RuleValue> values) { return new RuleValue(RuleType.list(element), values); }
    public RuleValue as(RuleType expected) {
        if (!expected.accepts(type)) throw new IllegalArgumentException("Expected " + expected.display());
        if (expected.equals(type)) return this;
        if (expected.kind() == RuleType.Kind.DECIMAL) return decimal(BigDecimal.valueOf(integer()));
        if (expected.kind() == RuleType.Kind.ENTITY) return new RuleValue(expected,value);
        return list(expected.element(), items());
    }
    public long integer() { return (Long)value; }
    public BigDecimal number() { return value instanceof BigDecimal n ? n : BigDecimal.valueOf(integer()); }
    public boolean bool() { return (Boolean)value; }
    @SuppressWarnings("unchecked")
    public List<RuleValue> items() { return (List<RuleValue>)value; }
}
