package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;

/** All mechanical state and randomness travel in the same authoritative commit control. */
public record MechanicsState(RandomStream random,long nextId,String scope,Map<String,Roll> rolls,Map<String,Card> cards,
                             Map<String,Pile> piles,Map<String,ActiveEffect> effects,Map<String,Use> uses,Map<String,QueueLock> queues) {
    public MechanicsState {
        Objects.requireNonNull(random);Objects.requireNonNull(scope);if(nextId<1)throw new IllegalArgumentException("Invalid mechanical identity counter");
        rolls=RulesState.map(rolls);cards=RulesState.map(cards);piles=RulesState.map(piles);effects=RulesState.map(effects);uses=RulesState.map(uses);queues=RulesState.map(queues);
    }
    public static MechanicsState initial(RandomStream random) { return new MechanicsState(random,1,"",Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of()); }
    public record Die(String type,int face,long value,List<String> symbols,int rerolls,boolean removed) {
        public Die { Objects.requireNonNull(type);symbols=List.copyOf(symbols); }
    }
    public record Roll(String id,String owner,CompiledRules.Audience visibility,String scope,List<Die> dice,List<String> cancelled,boolean active,boolean cancellationStarted) {
        public Roll { Objects.requireNonNull(id);Objects.requireNonNull(owner);Objects.requireNonNull(visibility);Objects.requireNonNull(scope);dice=List.copyOf(dice);cancelled=List.copyOf(cancelled); }
    }
    public enum PileKind { DECK, DISCARD, HAND }
    public record Card(String id,String type,String deck) { public Card { Objects.requireNonNull(id);Objects.requireNonNull(type);Objects.requireNonNull(deck); } }
    public record Pile(String id,PileKind kind,String deck,String definition,String owner,CompiledRules.Audience visibility,List<String> cards) {
        public Pile { Objects.requireNonNull(id);Objects.requireNonNull(kind);Objects.requireNonNull(owner);Objects.requireNonNull(visibility);cards=List.copyOf(cards); }
        Pile withCards(List<String> cards) { return new Pile(id,kind,deck,definition,owner,visibility,cards); }
    }
    public record ActiveEffect(String id,String entity,String key,long value,String owner,CompiledRules.Audience visibility,int round,String scope) {
        public ActiveEffect { Objects.requireNonNull(id);Objects.requireNonNull(entity);Objects.requireNonNull(key);Objects.requireNonNull(visibility);Objects.requireNonNull(scope); }
    }
    public record Use(String entity,String key,String period,int round,String scope) {
        public Use { Objects.requireNonNull(entity);Objects.requireNonNull(key);Objects.requireNonNull(period);Objects.requireNonNull(scope); }
    }
    public record QueueLock(String entity,String field,int locked) { public QueueLock { Objects.requireNonNull(entity);Objects.requireNonNull(field); } }
}
