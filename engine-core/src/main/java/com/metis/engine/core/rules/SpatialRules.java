package com.metis.engine.core.rules;

import java.util.*;

/** Versioned spatial policies and field bindings; coordinates are always board units. */
public record SpatialRules(Map<String,Board> boards,Map<String,Body> bodies,Map<String,Movement> movements,Map<String,Zone> zones) {
    public SpatialRules { boards=Map.copyOf(boards);bodies=Map.copyOf(bodies);movements=Map.copyOf(movements);zones=Map.copyOf(zones); }
    public enum Model { GRID, CONTINUOUS }
    public enum Metric { MANHATTAN, CHEBYSHEV, EUCLIDEAN }
    public enum Shape { POINT, CIRCLE, RECTANGLE }
    public record Board(Model model,double width,double height,String unit,Metric metric,boolean cornerCutting,boolean touchingAllowed,double tolerance) { }
    public record Body(String board,String x,String y,String facing,String placed,String solid,String opaque,Shape shape,double radius,double width,double height) { }
    public enum Traversal { SWEPT, DESTINATION }
    public record Movement(Traversal traversal,String allowance,double maximumTurn,boolean collisions,boolean ownerOnly) { }
    public record Zone(String board,int ownerSeat,double x,double y,double width,double height) { }
}
