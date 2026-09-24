package com.metis.engine.core.rules;

import java.util.*;

public record ScenarioRules(Map<String,Roster> rosters,Map<String,Damage> damage) {
    public ScenarioRules { rosters=Map.copyOf(rosters);damage=Map.copyOf(damage); }
    public record Roster(String entityType,String points,long budget,int minimum,int maximum,String unique,String faction,List<String> factions) {
        public Roster { factions=List.copyOf(factions); }
    }
    public enum OnZero { KEEP, REMOVE, MARK }
    public record Damage(String entityType,String health,List<String> absorbers,boolean capAtRemaining,OnZero onZero,String marker) {
        public Damage { absorbers=List.copyOf(absorbers); }
    }
}
