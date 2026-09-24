package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;

/** Player-facing projection. Continuations, commit patches and traces remain server-only. */
public record RulesView(String sessionId,long version,RulesState.Status status,int round,String phase,
                        String activePlayer,String activeEntity,List<String> players,Map<String,RuleValue> globals,
                        Map<String,EvaluationContext.Entity> entities,Pending decision,String outcome,List<String> winners,
                        List<RulesState.Event> events,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) MechanicalView mechanics) {
    public record RollView(String id,String owner,List<MechanicsState.Die> dice,List<String> cancelled,boolean active) {
        public RollView { dice=List.copyOf(dice);cancelled=List.copyOf(cancelled); }
    }
    public record PileView(String id,MechanicsState.PileKind kind,String owner,List<MechanicsState.Card> cards) {
        public PileView { cards=List.copyOf(cards); }
    }
    public record EffectView(String id,String entity,String key,long value) { }
    public record MechanicalView(Map<String,RollView> rolls,Map<String,PileView> piles,Map<String,EffectView> effects) {
        public MechanicalView { rolls=Map.copyOf(rolls);piles=Map.copyOf(piles);effects=Map.copyOf(effects); }
    }
    public record Pending(String id,String player,List<RuleValue> options) {
        public Pending { options=List.copyOf(options); }
    }
    public RulesView {
        players=List.copyOf(players);globals=Map.copyOf(globals);entities=Map.copyOf(entities);winners=List.copyOf(winners);events=List.copyOf(events);
    }
    public static RulesView project(CompiledRules definition,RulesState state,Set<String> controlledPlayers) {
        if(!definition.fingerprint().equals(state.definitionFingerprint()))throw new IllegalArgumentException("Definition mismatch");
        var globals=new TreeMap<String,RuleValue>();state.globals().forEach((name,value)->{
            if(definition.state().get(name).visibility()==CompiledRules.Audience.PUBLIC)globals.put(name,value);
        });
        var entities=new TreeMap<String,EvaluationContext.Entity>();
        state.entities().forEach((id,entity)->{
            var type=definition.entities().get(entity.type());var attributes=new TreeMap<String,RuleValue>();var resources=new TreeMap<String,Long>();
            entity.attributes().forEach((name,value)->{if(visible(type.attributes().get(name).visibility(),entity.owner(),controlledPlayers))attributes.put(name,value);});
            entity.resources().forEach((name,value)->{if(visible(type.resources().get(name).visibility(),entity.owner(),controlledPlayers))resources.put(name,value);});
            entities.put(id,new EvaluationContext.Entity(entity.type(),id,entity.owner(),attributes,resources));
        });
        var control=state.control();var choice=control.decision();
        Pending pending=choice==null?null:new Pending(choice.id(),choice.player(),visible(choice.visibility(),choice.player(),controlledPlayers)?choice.options():List.of());
        var events=state.events().stream().map(event->visible(event.visibility(),event.recipient(),controlledPlayers)?event:
                new RulesState.Event(event.sequence(),"rules.hidden",null,CompiledRules.Audience.PUBLIC,null,Map.of(),null)).toList();
        String active=control.status()!=RulesState.Status.RUNNING||control.flow().playerIndex()<0?null:state.players().get(control.flow().playerIndex());
        return new RulesView(state.sessionId(),state.version(),control.status(),control.flow().round(),control.flow().phase(),active,control.flow().entity(),state.players(),globals,entities,pending,control.outcome(),control.winners(),events,mechanics(state,controlledPlayers));
    }
    private static MechanicalView mechanics(RulesState state,Set<String> owned) {
        var m=state.control().mechanics();if(m==null)return null;
        var rolls=new TreeMap<String,RollView>();var piles=new TreeMap<String,PileView>();var effects=new TreeMap<String,EffectView>();
        m.rolls().forEach((id,roll)->{if(visible(roll.visibility(),roll.owner(),owned))rolls.put(id,new RollView(id,roll.owner(),roll.dice(),roll.cancelled(),roll.active()));});
        m.piles().forEach((id,pile)->{if(visible(pile.visibility(),pile.owner(),owned))piles.put(id,new PileView(id,pile.kind(),pile.owner(),pile.cards().stream().map(m.cards()::get).toList()));});
        m.effects().forEach((id,effect)->{if(visible(effect.visibility(),effect.owner(),owned))effects.put(id,new EffectView(id,effect.entity(),effect.key(),effect.value()));});
        return new MechanicalView(rolls,piles,effects);
    }
    private static boolean visible(CompiledRules.Audience audience,String owner,Set<String> controlled) {
        return audience==CompiledRules.Audience.PUBLIC||audience==CompiledRules.Audience.OWNER&&owner!=null&&controlled.contains(owner);
    }
    /** Internal query snapshot containing only mechanical objects visible to this seat. */
    static MechanicsState visibleMechanics(RulesState state,String actor) {
        var m=state.control().mechanics();if(m==null)return null;var owned=Set.of(actor);
        var rolls=new TreeMap<>(m.rolls());rolls.values().removeIf(r->!visible(r.visibility(),r.owner(),owned));
        var piles=new TreeMap<>(m.piles());piles.values().removeIf(p->!visible(p.visibility(),p.owner(),owned));
        var cardIds=new HashSet<String>();piles.values().forEach(p->cardIds.addAll(p.cards()));var cards=new TreeMap<>(m.cards());cards.keySet().retainAll(cardIds);
        var effects=new TreeMap<>(m.effects());effects.values().removeIf(e->!visible(e.visibility(),e.owner(),owned));
        var uses=new TreeMap<>(m.uses());uses.values().removeIf(u->!ownedEntity(state,u.entity(),actor));
        var queues=new TreeMap<>(m.queues());queues.values().removeIf(q->!ownedEntity(state,q.entity(),actor));
        // An inert stream prevents even internal speculation from accessing committed entropy.
        return new MechanicsState(RandomStream.tape(0),m.nextId(),m.scope(),rolls,cards,piles,effects,uses,queues);
    }
    private static boolean ownedEntity(RulesState state,String entity,String actor) { var e=state.entities().get(entity);return e!=null&&actor.equals(e.owner()); }
}
