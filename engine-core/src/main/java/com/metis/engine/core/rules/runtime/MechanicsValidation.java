package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.require;

final class MechanicsValidation {
    private MechanicsValidation() { }
    static void validate(CompiledRules rules,RulesState state) {
        var m=state.control().mechanics();int max=rules.limits().maxCollectionSize();
        for(var map:List.of(m.rolls(),m.cards(),m.piles(),m.effects(),m.uses(),m.queues()))require(map.size()<=max,"COLLECTION_LIMIT","/mechanics","Mechanical collection exceeds limit");
        require(!m.random().algorithm().equals("tape-v1")||m.random().position()<=m.random().tape().size(),"RANDOM_INVALID","/mechanics/random","Tape cursor exceeds tape");
        for(var entry:m.rolls().entrySet()) {
            var roll=entry.getValue();valid(entry.getKey().equals(roll.id())&&roll.owner()!=null&&state.players().contains(roll.owner())&&roll.scope()!=null&&roll.visibility()!=null&&roll.dice().size()<=max);
            valid(!roll.active()||roll.scope().equals(m.scope()));var symbols=new ArrayList<String>();
            for(var die:roll.dice()) {
                var type=rules.mechanics().dice().get(die.type());valid(type!=null&&die.face()>=0&&die.face()<type.faces().size()&&die.rerolls()>=0&&die.rerolls()<=type.maxRerolls());
                var face=type.faces().get(die.face());valid(die.value()==face.value()&&die.symbols().equals(face.symbols()));if(!die.removed())symbols.addAll(die.symbols());
            }
            valid(roll.cancellationStarted()||roll.cancelled().isEmpty());for(String symbol:roll.cancelled())valid(symbols.remove(symbol));
        }
        var located=new HashSet<String>();
        for(var entry:m.piles().entrySet()) {
            var pile=entry.getValue();valid(entry.getKey().equals(pile.id())&&pile.owner()!=null&&state.players().contains(pile.owner())&&pile.visibility()!=null&&pile.kind()!=null&&pile.cards().size()<=max);
            if(pile.kind()==MechanicsState.PileKind.HAND)valid(pile.deck()==null&&pile.definition()==null);
            else {
                valid(pile.deck()!=null&&pile.definition()!=null);
                var deck=m.piles().get(pile.deck());var policy=rules.mechanics().decks().get(pile.definition());
                valid(deck!=null&&deck.kind()==MechanicsState.PileKind.DECK&&policy!=null&&deck.definition().equals(pile.definition())&&deck.owner().equals(pile.owner()));
                if(pile.kind()==MechanicsState.PileKind.DISCARD)valid(pile.id().equals(deck.id()+":discard")&&pile.visibility()==policy.discardVisibility());
                else {
                    valid(pile.id().equals(pile.deck())&&m.piles().containsKey(pile.id()+":discard"));
                    valid(m.cards().values().stream().filter(card->card.deck().equals(pile.id())).map(MechanicsState.Card::type).sorted().toList().equals(policy.cards().stream().sorted().toList()));
                }
            }
            for(String id:pile.cards()) {
                var card=m.cards().get(id);valid(card!=null&&located.add(id));
                if(pile.kind()!=MechanicsState.PileKind.HAND)valid(card.deck().equals(pile.deck()));
            }
        }
        valid(located.equals(m.cards().keySet()));
        for(var entry:m.cards().entrySet()) {
            var card=entry.getValue();var deck=m.piles().get(card.deck());
            valid(entry.getKey().equals(card.id())&&deck!=null&&deck.kind()==MechanicsState.PileKind.DECK&&rules.mechanics().decks().get(deck.definition()).cards().contains(card.type()));
        }
        var counts=new HashMap<String,Integer>();
        for(var entry:m.effects().entrySet()) {
            var effect=entry.getValue();var entity=state.entities().get(effect.entity());var policy=rules.mechanics().effects().get(effect.key());
            valid(entry.getKey().equals(effect.id())&&entity!=null&&policy!=null&&effect.visibility()==policy.visibility()&&Objects.equals(effect.owner(),entity.owner())&&effect.scope()!=null&&effect.round()<=state.control().flow().round());
            if(policy.visibility()==CompiledRules.Audience.OWNER)valid(effect.owner()!=null);
            int count=counts.merge(effect.entity()+"/"+effect.key(),1,Integer::sum);valid(count<=(policy.stacking()==MechanicsRules.Stacking.REPLACE?1:policy.maximumStacks()));
        }
        for(var entry:m.uses().entrySet()) {
            var use=entry.getValue();valid(state.entities().containsKey(use.entity())&&Set.of("ROUND","ACTION","ACTIVATION").contains(use.period())&&use.scope()!=null);
            valid(entry.getKey().equals(use.entity().length()+":"+use.entity()+":"+use.key()+":"+use.period()));
            if(use.period().equals("ROUND"))valid(use.round()==state.control().flow().round());
        }
        for(var entry:m.queues().entrySet()) {
            var queue=entry.getValue();var entity=state.entities().get(queue.entity());valid(entity!=null);
            var field=rules.entities().get(entity.type()).attributes().get(queue.field());valid(field!=null&&field.type().equals(RuleType.list(RuleType.STRING)));
            valid(queue.locked()>=0&&queue.locked()<=entity.attributes().get(queue.field()).items().size());
            valid(entry.getKey().equals(queue.entity().length()+":"+queue.entity()+":"+queue.field()));
        }
    }
    private static void valid(boolean condition) { require(condition,"MECHANICS_INVALID","/mechanics","Mechanical checkpoint does not match captured rules"); }
}
