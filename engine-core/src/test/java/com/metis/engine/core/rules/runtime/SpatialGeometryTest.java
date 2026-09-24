package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.SpatialRules;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.runtime.SpatialGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialGeometryTest {
    static final double E=1e-6;
    static Footprint circle(double x,double y,double r) { return new Footprint(new Point(x,y),r,List.of()); }
    @Test void distanceAndHeadingUseDeclaredMetricsAndWrapAtZero() {
        assertEquals(5,measure(new Point(0,0),new Point(3,4),SpatialRules.Metric.EUCLIDEAN));
        assertEquals(7,measure(new Point(0,0),new Point(-3,4),SpatialRules.Metric.MANHATTAN));
        assertEquals(4,measure(new Point(0,0),new Point(-3,4),SpatialRules.Metric.CHEBYSHEV));
        assertEquals(350,heading(-10));assertEquals(10,heading(370));assertEquals(20,turn(350,10));assertEquals(180,turn(-90,90));
    }
    @Test void sweptCirclesDetectThinObstaclesAndDoNotSamplePastThem() {
        var start=circle(0,0,.5);var thin=rectangle(new Point(5,0),.0001,3,0);
        assertFalse(overlap(start,thin,true,E));assertFalse(overlap(circle(10,0,.5),thin,true,E));assertTrue(swept(start,new Point(10,0),thin,true,E));
        assertTrue(swept(start,new Point(10,0),circle(5,0,.001),true,E));assertFalse(swept(start,new Point(10,0),circle(5,2,1),true,E));
        assertFalse(overlap(circle(0,0,1),circle(2,0,1),true,E));assertTrue(overlap(circle(0,0,1),circle(2,0,1),false,E));
        assertFalse(overlap(circle(0,0,1),rectangle(new Point(2,0),2,2,0),true,E));assertTrue(overlap(circle(0,0,1),rectangle(new Point(2,0),2,2,0),false,E));
    }
    @Test void rotatedRectanglesUseConvexSweepAndFullFootprintBoardBounds() {
        var r=rectangle(new Point(2,2),2,4,90);assertTrue(inside(r,0,0,4,4,E));assertFalse(inside(r,0,0,3,4,E));assertFalse(inside(circle(0,0,1),0,0,10,10,E));
        var start=rectangle(new Point(1,3),1,2,0);var obstacle=rectangle(new Point(5,3),.1,1,45);
        assertTrue(swept(start,new Point(9,3),obstacle,true,E));assertFalse(swept(start,new Point(1,6),obstacle,true,E));
        assertTrue(swept(start,new Point(9,3),circle(5,3,.2),true,E));assertFalse(swept(start,new Point(9,3),circle(5,6,.2),true,E));
        assertTrue(swept(start,new Point(9,3),circle(5,3,0),true,E));assertFalse(swept(start,new Point(9,3),circle(5,6,0),false,E));
        assertTrue(swept(circle(0,3,0),new Point(8,3),obstacle,false,E));assertFalse(swept(circle(0,0,0),new Point(8,0),obstacle,true,E));
        var a=rectangle(new Point(0,0),2,2,0);var b=rectangle(new Point(2,0),2,2,0);
        assertFalse(overlap(a,b,true,E));assertTrue(overlap(a,b,false,E));assertTrue(overlap(a,rectangle(new Point(.5,.5),2,2,45),true,E));
    }
    @Test void edgeDistancesAndSightlinesHaveIndependentGeometricAnswers() {
        var box=rectangle(new Point(0,0),2,2,0);
        assertEquals(3,edgeDistance(box,rectangle(new Point(5,0),2,2,0)),E);
        assertEquals(3,edgeDistance(box,circle(5,0,1)),E);assertEquals(3,edgeDistance(circle(5,0,1),box),E);
        assertEquals(0,edgeDistance(box,rectangle(new Point(0,0),1,1,45)),E);assertEquals(0,edgeDistance(box,circle(0,0,.1)),E);
        assertEquals(2,edgeDistance(circle(0,0,1),circle(5,0,2)),E);
        assertFalse(obstructed(new Point(-2,1),new Point(2,1),box,true,E));assertTrue(obstructed(new Point(-2,1),new Point(2,1),box,false,E));
        assertTrue(obstructed(new Point(-2,0),new Point(2,0),box,true,E));assertFalse(obstructed(new Point(-2,2),new Point(2,2),box,false,E));
        assertTrue(obstructed(new Point(-2,0),new Point(2,0),circle(0,0,1),true,E));assertFalse(obstructed(new Point(-2,1),new Point(2,1),circle(0,0,1),true,E));
        assertFalse(obstructed(new Point(3,3),new Point(4,4),box,false,E));assertTrue(obstructed(new Point(-3,-3),new Point(3,3),box,true,E));
    }
}
