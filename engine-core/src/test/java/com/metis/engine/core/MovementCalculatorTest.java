package com.metis.engine.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementCalculatorTest {
    @Test
    void calculatesAChartDrivenManeuver() {
        UnitState unit = new UnitState(1, 10, "ship", 100, 100, 0, "ready", 5, 0, 0, 1);

        List<MovementCalculator.Point> path = MovementCalculator.path(unit, 2, List.of(1, 0), 100);
        MovementCalculator.Pose pose = MovementCalculator.finalPose(path,
                MovementCalculator.finalFacing(unit.facing(), List.of(1, 0)));

        assertEquals(292.39, pose.x(), .01);
        assertEquals(138.27, pose.y(), .01);
        assertEquals(22.5, pose.facing(), .001);
    }

    @Test
    void preservesVariableJointCountFromTheManeuverChart() {
        UnitState unit = new UnitState(1, 10, "ship", 100, 100, 0, "ready", 5, 0, 0, 1);

        List<Integer> yaw = List.of(0, 1, 1, 2);
        List<MovementCalculator.Point> path = MovementCalculator.path(unit, 4, yaw, 100);

        assertEquals(49, path.size());
        assertEquals(90, MovementCalculator.finalFacing(unit.facing(), yaw), .001);
    }
}
