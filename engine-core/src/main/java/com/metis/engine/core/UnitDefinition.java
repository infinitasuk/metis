package com.metis.engine.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Static unit profile supplied by one immutable game-definition revision. */
public record UnitDefinition(String code, String factionCode, double maximumMovementDistance,
                              double maximumAttackRange, int attackDamage, int maximumHullPoints,
                              int attackDice, int maximumShieldPoints, int defenseTokenCount,
                              String displayName, String classification,
                              Map<Integer, ManeuverProfile> maneuverProfiles, double baseRadius) {
    public UnitDefinition(String code, String factionCode, double maximumMovementDistance) {
        this(code, factionCode, maximumMovementDistance, 100, 1, 1, 1, 0, 0,
                code, "Unit");
    }

    public UnitDefinition(String code, String factionCode, double maximumMovementDistance,
                          double maximumAttackRange, int attackDamage, int maximumHullPoints) {
        this(code, factionCode, maximumMovementDistance, maximumAttackRange, attackDamage,
                maximumHullPoints, 1, 0, 0, code, "Unit");
    }

    public UnitDefinition(String code, String factionCode, double maximumMovementDistance,
                          double maximumAttackRange, int attackDamage, int maximumHullPoints,
                          int attackDice, int maximumShieldPoints, int defenseTokenCount) {
        this(code, factionCode, maximumMovementDistance, maximumAttackRange, attackDamage,
                maximumHullPoints, attackDice, maximumShieldPoints, defenseTokenCount, code, "Unit");
    }

    public UnitDefinition(String code, String factionCode, double maximumMovementDistance,
                          double maximumAttackRange, int attackDamage, int maximumHullPoints,
                          int attackDice, int maximumShieldPoints, int defenseTokenCount,
                          String displayName, String classification) {
        this(code, factionCode, maximumMovementDistance, maximumAttackRange, attackDamage,
                maximumHullPoints, attackDice, maximumShieldPoints, defenseTokenCount,
                displayName, classification, defaultManeuverProfiles(maximumMovementDistance),
                defaultBaseRadius(classification));
    }

    public UnitDefinition {
        if (code == null || code.isBlank() || factionCode == null || factionCode.isBlank()) {
            throw new IllegalArgumentException("unit code and faction are required");
        }
        displayName = displayName == null || displayName.isBlank() ? code : displayName;
        classification = classification == null || classification.isBlank() ? "Unit" : classification;
        if (maximumMovementDistance < 0) {
            throw new IllegalArgumentException("maximum movement distance must not be negative");
        }
        if (maximumAttackRange < 0 || attackDamage < 1 || maximumHullPoints < 1
                || attackDice < 1 || maximumShieldPoints < 0 || defenseTokenCount < 0) {
            throw new IllegalArgumentException("invalid combat profile");
        }
        maneuverProfiles = maneuverProfiles == null || maneuverProfiles.isEmpty()
                ? defaultManeuverProfiles(maximumMovementDistance) : Map.copyOf(maneuverProfiles);
        if (!(baseRadius > 0)) {
            throw new IllegalArgumentException("base radius must be positive");
        }
    }

    public int maximumSpeed() {
        return (int) Math.round(maximumMovementDistance);
    }

    public ManeuverProfile maneuverProfile(int speed) {
        return maneuverProfiles.get(speed);
    }

    private static Map<Integer, ManeuverProfile> defaultManeuverProfiles(double maximumMovementDistance) {
        int maximumSpeed = Math.max(0, (int) Math.round(maximumMovementDistance));
        Map<Integer, ManeuverProfile> profiles = new LinkedHashMap<>();
        for (int speed = 0; speed <= maximumSpeed; speed++) {
            int joints = Math.max(1, speed);
            profiles.put(speed, new ManeuverProfile(speed, java.util.Collections.nCopies(joints, speed == 0 ? 0 : 1)));
        }
        return profiles;
    }

    private static double defaultBaseRadius(String classification) {
        return "squadron".equalsIgnoreCase(classification) ? 25 : 50;
    }

}
