package com.metis.engine.core.rules;

import java.util.*;

/** Definition-owned mechanical policies. Nothing here identifies a particular game. */
public record MechanicsRules(Map<String,Die> dice,Map<String,ResultTable> resultTables,
                             Map<String,Deck> decks,Map<String,Effect> effects) {
    public static final Set<String> MODULES=Set.of("dice","cards","resources","effects","queues");
    public MechanicsRules { dice=Map.copyOf(dice);resultTables=Map.copyOf(resultTables);decks=Map.copyOf(decks);effects=Map.copyOf(effects); }
    public record Face(int weight,long value,List<String> symbols) { public Face { symbols=List.copyOf(symbols); } }
    public record Die(List<Face> faces,int maxRerolls,int totalWeight) { public Die { faces=List.copyOf(faces); } }
    public record ResultTable(Map<String,Long> symbols,long valueMultiplier,boolean ignoreUnmapped) { public ResultTable { symbols=Map.copyOf(symbols); } }
    public enum Exhaustion { REJECT, PARTIAL, RESHUFFLE }
    public record Deck(List<String> cards,boolean shuffle,Exhaustion exhaustion,CompiledRules.Audience discardVisibility) { public Deck { cards=List.copyOf(cards); } }
    public enum Duration { ACTION, ACTIVATION, ROUND, PERSISTENT }
    public enum Stacking { ADD, MAX, REPLACE }
    public record Effect(Duration duration,Stacking stacking,int maximumStacks,CompiledRules.Audience visibility) { }
}
