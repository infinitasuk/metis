package com.metis.engine.core;

import java.util.List;

/** Maximum signed yaw clicks available at each maneuver-tool joint for a speed. */
public record ManeuverProfile(int speed, List<Integer> maximumYawClicks) {
    public ManeuverProfile {
        if (speed < 0 || maximumYawClicks == null || maximumYawClicks.isEmpty()) {
            throw new IllegalArgumentException("a maneuver speed and yaw chart are required");
        }
        maximumYawClicks = List.copyOf(maximumYawClicks);
        if (maximumYawClicks.stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("maneuver yaw limits must not be negative");
        }
    }
}
