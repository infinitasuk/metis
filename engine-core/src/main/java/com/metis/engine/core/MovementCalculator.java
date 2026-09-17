package com.metis.engine.core;

import java.util.ArrayList;
import java.util.List;

/** Calculates a deterministic maneuver in board coordinates. */
public final class MovementCalculator {
    public static final double YAW_CLICK_DEGREES = 22.5;
    private static final int COLLISION_SAMPLES_PER_SEGMENT = 12;

    private MovementCalculator() {}

    public static List<Point> path(UnitState unit, int speed, List<Integer> yawClicks,
                                   double distancePerSpeed) {
        if (speed < 0 || distancePerSpeed < 0 || yawClicks == null || yawClicks.isEmpty()) {
            throw new IllegalArgumentException("a maneuver with at least one joint is required");
        }
        double heading = Math.toRadians(unit.facing());
        double x = unit.positionX();
        double y = unit.positionY();
        double segmentDistance = speed * distancePerSpeed / yawClicks.size();
        List<Point> points = new ArrayList<>();
        points.add(new Point(x, y));
        for (int segment = 0; segment < yawClicks.size(); segment++) {
            double startX = x;
            double startY = y;
            for (int sample = 1; sample <= COLLISION_SAMPLES_PER_SEGMENT; sample++) {
                double fraction = (double) sample / COLLISION_SAMPLES_PER_SEGMENT;
                x = startX + Math.cos(heading) * segmentDistance * fraction;
                y = startY + Math.sin(heading) * segmentDistance * fraction;
                points.add(new Point(x, y));
            }
            heading += Math.toRadians(yawClicks.get(segment) * YAW_CLICK_DEGREES);
        }
        return points;
    }

    public static Pose finalPose(List<Point> path, double facing) {
        Point endpoint = path.getLast();
        return new Pose(endpoint.x(), endpoint.y(), normalizeDegrees(facing));
    }

    public static double finalFacing(double initialFacing, List<Integer> yawClicks) {
        return normalizeDegrees(initialFacing + yawClicks.stream()
                .mapToDouble(Integer::doubleValue)
                .sum() * YAW_CLICK_DEGREES);
    }

    public static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }

    public record Point(double x, double y) {}

    public record Pose(double x, double y, double facing) {}
}
