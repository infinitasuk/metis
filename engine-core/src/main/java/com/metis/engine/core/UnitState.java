package com.metis.engine.core;

import java.util.Map;

/** Mutable runtime state for one unit instance. */
public record UnitState(long unitId, long playerId, String unitCode,
                         double positionX, double positionY, double facing,
                          String status, Integer hullPoints, Integer shieldPoints,
                          Integer defenseTokens, int currentSpeed,
                          Map<String, Integer> shieldZones,
                          Map<String, Integer> readyDefenseTokens,
                          Map<String, Integer> exhaustedDefenseTokens) {
    public UnitState(long unitId, long playerId, String unitCode,
                     double positionX, double positionY, double facing, String status) {
        this(unitId, playerId, unitCode, positionX, positionY, facing, status, 1, 0, 0, 0, Map.of());
    }

    public UnitState(long unitId, long playerId, String unitCode,
                     double positionX, double positionY, double facing, String status,
                     int hullPoints) {
        this(unitId, playerId, unitCode, positionX, positionY, facing, status, hullPoints, 0, 0, 0, Map.of());
    }

    public UnitState(long unitId, long playerId, String unitCode,
                     double positionX, double positionY, double facing, String status,
                     Integer hullPoints, Integer shieldPoints, Integer defenseTokens, int currentSpeed) {
        this(unitId, playerId, unitCode, positionX, positionY, facing, status,
                hullPoints, shieldPoints, defenseTokens, currentSpeed, Map.of());
    }

    public UnitState {
        if (unitId < 0 || playerId < 0) {
            throw new IllegalArgumentException("unit and player identifiers must not be negative");
        }
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("unitCode is required");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        hullPoints = hullPoints == null ? 1 : hullPoints;
        shieldPoints = shieldPoints == null ? 0 : shieldPoints;
        defenseTokens = defenseTokens == null ? 0 : defenseTokens;
        shieldZones = shieldZones == null ? Map.of() : Map.copyOf(shieldZones);
        readyDefenseTokens = normalizeTokens(readyDefenseTokens, defenseTokens);
        exhaustedDefenseTokens = normalizeTokens(exhaustedDefenseTokens, 0);
        defenseTokens = readyDefenseTokens.values().stream().mapToInt(Integer::intValue).sum();
        if (hullPoints < 0 || shieldPoints < 0 || defenseTokens < 0 || currentSpeed < 0) {
            throw new IllegalArgumentException("unit resources must not be negative");
        }
    }

    public UnitState(long unitId, long playerId, String unitCode,
                     double positionX, double positionY, double facing,
                     String status, Integer hullPoints, Integer shieldPoints,
                     Integer defenseTokens, int currentSpeed,
                     Map<String, Integer> shieldZones) {
        this(unitId, playerId, unitCode, positionX, positionY, facing, status,
                hullPoints, shieldPoints, defenseTokens, currentSpeed, shieldZones, Map.of(), Map.of());
    }

    private static Map<String, Integer> normalizeTokens(Map<String, Integer> tokens, int fallbackCount) {
        if (tokens != null && !tokens.isEmpty()) {
            Map<String, Integer> result = new java.util.LinkedHashMap<>();
            tokens.forEach((type, count) -> {
                if (type == null || type.isBlank() || count == null || count < 0) {
                    throw new IllegalArgumentException("defense token state is invalid");
                }
                if (count > 0) result.put(type, count);
            });
            return Map.copyOf(result);
        }
        return fallbackCount > 0 ? Map.of("generic", fallbackCount) : Map.of();
    }
}
