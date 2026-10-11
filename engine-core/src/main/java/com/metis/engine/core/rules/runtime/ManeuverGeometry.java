package com.metis.engine.core.rules.runtime;

import java.util.*;
import static com.metis.engine.core.rules.runtime.SpatialGeometry.*;

/** Rigid-body placement from a segmented tool and a local attachment offset. All dimensions are board units. */
public final class ManeuverGeometry {
    private ManeuverGeometry() { }
    public record Trace(int speed,List<Integer> yaw,String side,List<Point> tool,double toolWidth) {
        public Trace { yaw=List.copyOf(yaw);tool=List.copyOf(tool); }
    }
    public record Result(Point destination,double heading,List<Point> path,Trace trace,double distance) {
        public Result { path=List.copyOf(path); }
    }
    private static Point rotate(double x,double y,double facing) {
        double radians=StrictMath.toRadians(heading(facing)),c=StrictMath.cos(radians),s=StrictMath.sin(radians);
        return new Point(x*c-y*s,x*s+y*c);
    }
    public static Result plan(Point origin,double facing,List<Integer> yaw,String side,double segment,double angle,double along,double across,double toolWidth) {
        // Board coordinates match the renderer: x right, y down, positive heading clockwise.
        double sign=switch(side) { case "PORT"->-1;case "STARBOARD"->1;default->throw new IllegalArgumentException("Unknown tool side"); };
        var offset=rotate(along,sign*across,facing);var pivot=origin.plus(offset);var tool=new ArrayList<Point>();tool.add(pivot);
        var path=new ArrayList<Point>();double current=heading(facing),travel=0;Point previous=origin;
        for(int clicks:yaw) {
            pivot=pivot.plus(rotate(segment,0,current));tool.add(pivot);current=heading(current+clicks*angle);
            var center=pivot.minus(rotate(along,sign*across,current));path.add(center);travel+=distance(previous,center);previous=center;
        }
        return new Result(previous,current,path,new Trace(yaw.size(),yaw,side,tool,toolWidth),travel);
    }
}
