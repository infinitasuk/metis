package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.SpatialRules;
import java.util.*;

/** Analytic 2D geometry for circles and fixed-orientation convex rectangular bases. */
public final class SpatialGeometry {
    private SpatialGeometry() { }
    public record Point(double x,double y) {
        Point plus(Point p) { return new Point(x+p.x,y+p.y); }
        Point minus(Point p) { return new Point(x-p.x,y-p.y); }
    }
    public record Footprint(Point center,double radius,List<Point> polygon) { public Footprint { polygon=List.copyOf(polygon); } }
    public static double heading(double value) { double h=value%360;return h<0?h+360:h; }
    public static double turn(double from,double to) { double d=Math.abs(heading(from)-heading(to));return Math.min(d,360-d); }
    public static double distance(Point a,Point b) { return StrictMath.hypot(a.x-b.x,a.y-b.y); }
    public static double measure(Point a,Point b,SpatialRules.Metric metric) {
        double dx=Math.abs(a.x-b.x),dy=Math.abs(a.y-b.y);
        return switch(metric) { case EUCLIDEAN->StrictMath.hypot(dx,dy);case MANHATTAN->dx+dy;case CHEBYSHEV->Math.max(dx,dy); };
    }
    public static Footprint footprint(SpatialRules.Body body,SpatialRules.Board board,Point position,double heading) {
        if(board.model()==SpatialRules.Model.GRID)return rectangle(new Point(position.x+.5,position.y+.5),1,1,0);
        if(body.shape()==SpatialRules.Shape.RECTANGLE)return rectangle(position,body.width(),body.height(),heading);
        return new Footprint(position,body.shape()==SpatialRules.Shape.CIRCLE?body.radius():0,List.of());
    }
    public static Footprint rectangle(Point center,double width,double height,double heading) {
        double angle=StrictMath.toRadians(heading(heading)),cos=StrictMath.cos(angle),sin=StrictMath.sin(angle);var points=new ArrayList<Point>();
        for(var p:List.of(new Point(-width/2,-height/2),new Point(width/2,-height/2),new Point(width/2,height/2),new Point(-width/2,height/2)))
            points.add(new Point(center.x+p.x*cos-p.y*sin,center.y+p.x*sin+p.y*cos));
        return new Footprint(center,0,points);
    }
    public static boolean inside(Footprint body,double x,double y,double width,double height,double epsilon) {
        if(body.polygon.isEmpty())return body.center.x-body.radius>=x-epsilon&&body.center.y-body.radius>=y-epsilon&&body.center.x+body.radius<=x+width+epsilon&&body.center.y+body.radius<=y+height+epsilon;
        return body.polygon.stream().allMatch(p->p.x>=x-epsilon&&p.y>=y-epsilon&&p.x<=x+width+epsilon&&p.y<=y+height+epsilon);
    }
    public static boolean overlap(Footprint a,Footprint b,boolean touching,double epsilon) { return swept(a,a.center,b,touching,epsilon); }
    public static boolean swept(Footprint moving,Point destination,Footprint obstacle,boolean touchingAllowed,double epsilon) {
        if(moving.polygon.isEmpty()) {
            if(obstacle.polygon.isEmpty())return within(pointSegment(obstacle.center,moving.center,destination),moving.radius+obstacle.radius,touchingAllowed,epsilon);
            if(moving.radius==0)return segmentPolygon(moving.center,destination,obstacle.polygon,touchingAllowed?epsilon:-epsilon);
            return within(segmentPolygonDistance(moving.center,destination,obstacle.polygon),moving.radius,touchingAllowed,epsilon);
        }
        var points=new ArrayList<>(moving.polygon);Point delta=destination.minus(moving.center);moving.polygon.forEach(p->points.add(p.plus(delta)));var swept=hull(points);
        if(obstacle.polygon.isEmpty()) {
            if(obstacle.radius==0)return segmentPolygon(obstacle.center,obstacle.center,swept,touchingAllowed?epsilon:-epsilon);
            return within(pointPolygon(obstacle.center,swept),obstacle.radius,touchingAllowed,epsilon);
        }
        return polygonsOverlap(swept,obstacle.polygon,touchingAllowed,epsilon);
    }
    public static boolean obstructed(Point a,Point b,Footprint obstacle,boolean touchingAllowed,double epsilon) {
        if(obstacle.polygon.isEmpty())return within(pointSegment(obstacle.center,a,b),obstacle.radius,touchingAllowed,epsilon);
        return segmentPolygon(a,b,obstacle.polygon,touchingAllowed?epsilon:-epsilon);
    }
    public static double edgeDistance(Footprint a,Footprint b) {
        if(a.polygon.isEmpty()&&b.polygon.isEmpty())return Math.max(0,distance(a.center,b.center)-a.radius-b.radius);
        if(a.polygon.isEmpty())return Math.max(0,pointPolygon(a.center,b.polygon)-a.radius);
        if(b.polygon.isEmpty())return Math.max(0,pointPolygon(b.center,a.polygon)-b.radius);
        if(polygonsOverlap(a.polygon,b.polygon,false,0))return 0;
        double minimum=Double.POSITIVE_INFINITY;
        for(int i=0;i<a.polygon.size();i++)minimum=Math.min(minimum,segmentPolygonDistance(a.polygon.get(i),a.polygon.get((i+1)%a.polygon.size()),b.polygon));
        return minimum;
    }
    private static boolean within(double distance,double radius,boolean touchingAllowed,double epsilon) { return touchingAllowed?distance<radius-epsilon:distance<=radius+epsilon; }
    private static double dot(Point a,Point b) { return a.x*b.x+a.y*b.y; }
    private static double cross(Point a,Point b) { return a.x*b.y-a.y*b.x; }
    private static double pointSegment(Point p,Point a,Point b) {
        Point v=b.minus(a);double length=dot(v,v);if(length==0)return distance(p,a);
        double t=Math.max(0,Math.min(1,dot(p.minus(a),v)/length));return distance(p,new Point(a.x+t*v.x,a.y+t*v.y));
    }
    private static boolean segmentPolygon(Point a,Point b,List<Point> polygon,double margin) {
        double low=0,high=1;Point delta=b.minus(a);
        for(int i=0;i<polygon.size();i++) {
            Point start=polygon.get(i),edge=polygon.get((i+1)%polygon.size()).minus(start);
            double origin=cross(edge,a.minus(start)),slope=cross(edge,delta),threshold=margin*StrictMath.hypot(edge.x,edge.y);
            if(Math.abs(slope)<1e-15) { if(origin<threshold)return false; }
            else { double t=(threshold-origin)/slope;if(slope>0)low=Math.max(low,t);else high=Math.min(high,t);if(low>high)return false; }
        }
        return low<=high;
    }
    private static double pointPolygon(Point point,List<Point> polygon) {
        if(segmentPolygon(point,point,polygon,0))return 0;double min=Double.POSITIVE_INFINITY;
        for(int i=0;i<polygon.size();i++)min=Math.min(min,pointSegment(point,polygon.get(i),polygon.get((i+1)%polygon.size())));return min;
    }
    private static double segmentPolygonDistance(Point a,Point b,List<Point> polygon) {
        if(segmentPolygon(a,b,polygon,0))return 0;double min=Math.min(pointPolygon(a,polygon),pointPolygon(b,polygon));
        for(var p:polygon)min=Math.min(min,pointSegment(p,a,b));return min;
    }
    private static boolean polygonsOverlap(List<Point> a,List<Point> b,boolean touching,double epsilon) {
        for(var polygon:List.of(a,b))for(int i=0;i<polygon.size();i++) {
            var edge=polygon.get((i+1)%polygon.size()).minus(polygon.get(i));double length=StrictMath.hypot(edge.x,edge.y);if(length==0)continue;
            var axis=new Point(-edge.y/length,edge.x/length);double amin=Double.POSITIVE_INFINITY,amax=Double.NEGATIVE_INFINITY,bmin=amin,bmax=amax;
            for(var p:a) { double value=dot(axis,p);amin=Math.min(amin,value);amax=Math.max(amax,value); }
            for(var p:b) { double value=dot(axis,p);bmin=Math.min(bmin,value);bmax=Math.max(bmax,value); }
            double overlap=Math.min(amax,bmax)-Math.max(amin,bmin);if(touching?overlap<=epsilon:overlap< -epsilon)return false;
        }
        return true;
    }
    private static List<Point> hull(List<Point> input) {
        var sorted=input.stream().distinct().sorted(Comparator.comparingDouble(Point::x).thenComparingDouble(Point::y)).toList();var result=new ArrayList<Point>();
        for(var p:sorted) { while(result.size()>=2&&cross(result.getLast().minus(result.get(result.size()-2)),p.minus(result.getLast()))<=0)result.removeLast();result.add(p); }
        int lower=result.size();for(int i=sorted.size()-2;i>=0;i--) { var p=sorted.get(i);while(result.size()>lower&&cross(result.getLast().minus(result.get(result.size()-2)),p.minus(result.getLast()))<=0)result.removeLast();result.add(p); }
        result.removeLast();return result;
    }
}
