package com.metis.engine.core.rules;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** The same definition-owned roster policy is used by setup and pre-game validation. */
public final class RosterValidator {
    private RosterValidator() { }

    public static long validate(ScenarioRules.Roster policy, String owner, List<EvaluationContext.Entity> members) {
        require(members.size() >= policy.minimum() && members.size() <= policy.maximum(),
                "ROSTER_COUNT", "Roster size outside bounds");
        long total = 0;
        var identities = new HashSet<String>();
        var unique = new HashSet<String>();
        for (var entity : members) {
            require(entity != null && entity.type().equals(policy.entityType())
                    && Objects.equals(entity.owner(), owner) && identities.add(entity.id()),
                    "ROSTER_MEMBER", "Invalid roster member");
            long cost = attribute(entity, policy.points()).integer();
            require(cost >= 0, "ROSTER_COST", "Negative roster cost");
            total = Math.addExact(total, cost);
            if (!policy.unique().isEmpty()) {
                String key = (String) attribute(entity, policy.unique()).value();
                require(key.isEmpty() || unique.add(key), "ROSTER_UNIQUE", "Unique roster entry repeated");
            }
            if (!policy.faction().isEmpty() && !policy.factions().isEmpty()) {
                require(policy.factions().contains((String) attribute(entity, policy.faction()).value()),
                        "ROSTER_FACTION", "Faction is not allowed");
            }
        }
        require(total <= policy.budget(), "ROSTER_BUDGET", "Roster exceeds points budget");
        return total;
    }

    private static RuleValue attribute(EvaluationContext.Entity entity, String field) {
        var value = entity.attributes().get(field);
        require(value != null, "ROSTER_FIELD", "Roster field unavailable");
        return value;
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new RuleEvaluationException(code, "/scenario", message);
    }
}
