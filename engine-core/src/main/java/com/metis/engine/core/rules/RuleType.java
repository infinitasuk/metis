package com.metis.engine.core.rules;

import java.util.Objects;

/** Structural language types. Entity types are definition-owned identifiers. */
public record RuleType(Kind kind, String entityType, RuleType element) {
    public enum Kind { INTEGER, DECIMAL, BOOLEAN, STRING, PLAYER, ENTITY, LIST }
    public static final RuleType INTEGER = scalar(Kind.INTEGER);
    public static final RuleType DECIMAL = scalar(Kind.DECIMAL);
    public static final RuleType BOOLEAN = scalar(Kind.BOOLEAN);
    public static final RuleType STRING = scalar(Kind.STRING);
    public static final RuleType PLAYER = scalar(Kind.PLAYER);
    /** Registry-only generic entity argument/result; source declarations use concrete types. */
    public static final RuleType ENTITY = entity("*");

    public RuleType {
        Objects.requireNonNull(kind);
        if ((kind == Kind.ENTITY) != (entityType != null)
                || (kind == Kind.LIST) != (element != null)) throw new IllegalArgumentException("Invalid type shape");
    }
    private static RuleType scalar(Kind kind) { return new RuleType(kind, null, null); }
    public static RuleType entity(String name) { return new RuleType(Kind.ENTITY, Objects.requireNonNull(name), null); }
    public static RuleType list(RuleType element) { return new RuleType(Kind.LIST, null, Objects.requireNonNull(element)); }
    public boolean numeric() { return kind == Kind.INTEGER || kind == Kind.DECIMAL; }
    public boolean accepts(RuleType actual) {
        return equals(actual) || (kind == Kind.DECIMAL && actual.kind == Kind.INTEGER)
                || (kind == Kind.ENTITY && entityType.equals("*") && actual.kind == Kind.ENTITY)
                || (kind == Kind.LIST && actual.kind == Kind.LIST && element.accepts(actual.element));
    }
    public String display() {
        return kind == Kind.ENTITY ? "entity:" + entityType : kind == Kind.LIST ? "list<" + element.display() + ">" : kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
