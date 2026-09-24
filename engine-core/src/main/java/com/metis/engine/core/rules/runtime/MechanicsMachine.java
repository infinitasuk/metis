package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.*;
import static com.metis.engine.core.rules.runtime.MechanicsState.*;

/** Reusable mechanical handlers operating only on the current isolated transition. */
final class MechanicsMachine {
    private static final List<String> QUERIES=OperationRegistry.standard().operations().values().stream()
            .filter(op->MechanicsRules.MODULES.contains(op.name().split("\\.")[0])&&op.effects().equals(Set.of(OperationRegistry.Effect.PURE))).map(OperationRegistry.Operation::name).toList();
    private final RuntimeMachine machine;
    private final CompiledRules rules;
    private RandomStream random;
    private long nextId;
    private String scope;
    private final Map<String,Roll> rolls;
    private final Map<String,Card> cards;
    private final Map<String,Pile> piles;
    private final Map<String,ActiveEffect> effects;
    private final Map<String,Use> uses;
    private final Map<String,QueueLock> queues;
    MechanicsMachine(RuntimeMachine machine,MechanicsState state) {
        this.machine=machine;rules=machine.rules;random=state.random();nextId=state.nextId();scope=state.scope();
        rolls=new TreeMap<>(state.rolls());cards=new TreeMap<>(state.cards());piles=new TreeMap<>(state.piles());effects=new TreeMap<>(state.effects());uses=new TreeMap<>(state.uses());queues=new TreeMap<>(state.queues());
    }
    MechanicsState snapshot() { return new MechanicsState(random,nextId,scope,rolls,cards,piles,effects,uses,queues); }
    private String id(String prefix) { String id=prefix+nextId;nextId=Math.incrementExact(nextId);return id; }
    private void tick() { machine.budget.spend("/mechanics"); }
    private int maximum() { return rules.limits().maxCollectionSize(); }
    private void capacity(int size) { require(size<=maximum(),"COLLECTION_LIMIT","/mechanics","Mechanical collection exceeds limit"); }
    private int draw(int bound) { tick();var result=random.draw(bound);random=result.next();return result.ticket(); }
    private void event(String type,CompiledRules.Audience visibility,String owner,Map<String,RuleValue> values) {
        machine.event("rules."+type,visibility,visibility==CompiledRules.Audience.OWNER?owner:null,values,"/mechanics");
    }
    private static String text(Map<String,RuleValue> args,String key) { return (String)args.get(key).value(); }
    private static long number(Map<String,RuleValue> args,String key) { return args.get(key).integer(); }
    private static List<String> strings(RuleValue value) { return value.items().stream().map(item->(String)item.value()).toList(); }
    private static RuleValue strings(List<String> values) { return RuleValue.list(RuleType.STRING,values.stream().map(RuleValue::text).toList()); }
    private static RuleValue integers(List<Long> values) { return RuleValue.list(RuleType.INTEGER,values.stream().map(RuleValue::integer).toList()); }
    private static CompiledRules.Audience audience(String value) { return CompiledRules.Audience.valueOf(value); }
    private EvaluationContext.Entity entity(RuleValue value) {
        machine.checked(value,RuleType.ENTITY,"/mechanics/entity",true);return machine.entities.get(((RuleValue.EntityRef)value.value()).id());
    }
    private String owner(Map<String,RuleValue> args) { return (String)machine.checked(args.get("owner"),RuleType.PLAYER,"/mechanics/owner",true).value(); }
    Map<String,EvaluationContext.QueryHandler> queries() {
        var result=new TreeMap<String,EvaluationContext.QueryHandler>();
        QUERIES.forEach(name->result.put(name,(args,ignored)->query(name,args)));
        return result;
    }
    void beginScope(RulesState.Purpose purpose) { scope=machine.before.sessionId()+":"+(machine.before.version()+1)+":"+purpose+":"+Objects.toString(machine.flow.phase(),""); }
    void finishScope() {
        for(var roll:new ArrayList<>(rolls.values()))if(roll.active()&&roll.scope().equals(scope))close(roll);
        for(var effect:new ArrayList<>(effects.values())) {
            var policy=rules.mechanics().effects().get(effect.key());
            if(policy.duration()==MechanicsRules.Duration.ACTION&&effect.scope().equals(scope)
                    ||machine.status==RulesState.Status.FINISHED&&policy.duration()!=MechanicsRules.Duration.PERSISTENT)expire(effect);
        }
        uses.values().removeIf(use->use.period().equals("ACTION")&&use.scope().equals(scope));scope="";
        if(machine.status==RulesState.Status.FINISHED)uses.clear();
    }
    private String activationScope(String actor) {
        String entity=Objects.toString(machine.flow.entity(),"");String value=machine.flow.round()+":"+machine.flow.phase()+":"+actor.length()+":"+actor+":"+entity;
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    void finishActivation(String actor) {
        String key=activationScope(actor);
        for(var effect:new ArrayList<>(effects.values()))if(rules.mechanics().effects().get(effect.key()).duration()==MechanicsRules.Duration.ACTIVATION&&effect.scope().equals(key))expire(effect);
        uses.values().removeIf(use->use.period().equals("ACTIVATION")&&use.scope().equals(key));
    }
    void roundChanged(int round) {
        for(var effect:new ArrayList<>(effects.values()))if(rules.mechanics().effects().get(effect.key()).duration()==MechanicsRules.Duration.ROUND&&effect.round()!=round)expire(effect);
        uses.values().removeIf(use->use.period().equals("ROUND")&&use.round()!=round);
    }
    void entityRemoved(String id) { effects.values().removeIf(e->e.entity().equals(id));uses.values().removeIf(e->e.entity().equals(id));queues.values().removeIf(e->e.entity().equals(id)); }
    RuleValue execute(String op,Map<String,RuleValue> args) {
        tick();
        return switch(op) {
            case "dice.roll" -> {
                capacity(rolls.size()+1);String id=id("roll-");String owner=owner(args);var visibility=audience(text(args,"visibility"));
                var dice=rollDice(strings(args.get("dice")));var roll=new Roll(id,owner,visibility,scope,dice,List.of(),true,false);rolls.put(id,roll);rollEvent("diceRolled",roll);yield RuleValue.text(id);
            }
            case "dice.add" -> {
                var roll=modifiable(text(args,"roll"),false);var types=strings(args.get("dice"));capacity(roll.dice().size()+types.size());
                var dice=new ArrayList<>(roll.dice());dice.addAll(rollDice(types));saveDice(roll,dice,"diceAdded");yield null;
            }
            case "dice.remove","dice.reroll" -> {
                var roll=modifiable(text(args,"roll"),false);var selected=indices(args.get("indices"),roll);
                if(op.equals("dice.reroll")) {
                    long max=number(args,"maximum");require(max>=0&&max<=maximum()&&selected.size()<=max,"REROLL_LIMIT","/mechanics","Reroll allowance exceeded");
                    var allowed=indices(args.get("allowed"),roll);require(allowed.containsAll(selected),"REROLL_INELIGIBLE","/mechanics","Selected dice are not eligible");
                    for(int index:selected)require(roll.dice().get(index).rerolls()<die(roll.dice().get(index).type()).maxRerolls(),"REROLL_LIMIT","/mechanics","Die has reached its reroll limit");
                }
                var dice=new ArrayList<>(roll.dice());
                for(int index:selected) { var old=dice.get(index);dice.set(index,op.equals("dice.remove")?new Die(old.type(),old.face(),old.value(),old.symbols(),old.rerolls(),true):rollDie(old.type(),old.rerolls()+1)); }
                saveDice(roll,dice,op.equals("dice.remove")?"diceRemoved":"diceRerolled");yield null;
            }
            case "dice.cancel" -> {
                var roll=modifiable(text(args,"roll"),true);var selected=strings(args.get("symbols"));long max=number(args,"maximum");
                require(max>=0&&max<=maximum()&&selected.size()<=max,"CANCELLATION_LIMIT","/mechanics","Cancellation allowance exceeded");
                var available=symbols(roll);for(String symbol:selected)require(available.remove(symbol),"SYMBOL_UNAVAILABLE","/mechanics","Symbol is not available for cancellation");
                var cancelled=new ArrayList<>(roll.cancelled());cancelled.addAll(selected);
                var next=new Roll(roll.id(),roll.owner(),roll.visibility(),roll.scope(),roll.dice(),cancelled,true,true);rolls.put(next.id(),next);rollEvent("symbolsCancelled",next);yield null;
            }
            case "dice.close" -> { close(modifiable(text(args,"roll"),true));yield null; }
            case "dice.clear" -> { var roll=roll(text(args,"roll"));require(!roll.active(),"ROLL_ACTIVE","/mechanics","Close roll before clearing it");rolls.remove(roll.id());yield null; }
            case "cards.create" -> RuleValue.text(createDeck(args));
            case "cards.hand" -> {
                capacity(piles.size()+1);String id=id("hand-");piles.put(id,new Pile(id,PileKind.HAND,null,null,owner(args),audience(text(args,"visibility")),List.of()));yield RuleValue.text(id);
            }
            case "cards.shuffle" -> { var deck=deck(text(args,"deck"));piles.put(deck.id(),deck.withCards(shuffled(deck.cards())));event("deckShuffled",CompiledRules.Audience.SERVER,deck.owner(),Map.of());yield null; }
            case "cards.draw" -> drawCards(args);
            case "cards.discard","cards.move" -> { moveCards(op,args);yield null; }
            case "resources.transfer" -> {
                var from=entity(args.get("from"));var to=entity(args.get("to"));String a=text(args,"fromResource"),b=text(args,"toResource");long amount=number(args,"amount");
                require(amount>=0&&!(from.id().equals(to.id())&&a.equals(b)),"RESOURCE_TRANSFER","/mechanics","Invalid transfer");
                changeResources(from,a,-amount,to,b,amount);yield null;
            }
            case "resources.convert" -> {
                var entity=entity(args.get("entity"));long spend=number(args,"spend"),gain=number(args,"gain");String from=text(args,"from"),to=text(args,"to");
                require(spend>0&&gain>0&&!from.equals(to),"RESOURCE_CONVERSION","/mechanics","Invalid conversion");changeResources(entity,from,-spend,entity,to,gain);yield null;
            }
            case "resources.reset" -> { var e=entity(args.get("entity"));setResource(e,text(args,"resource"),number(args,"value"));yield null; }
            case "resources.once" -> {
                var e=entity(args.get("entity"));String key=text(args,"key"),period=period(args);String id=useKey(e.id(),key,period);
                if(!period.equals("ROUND"))require(machine.purpose==RulesState.Purpose.ACTION,"EFFECT_SCOPE","/mechanics","Usage period requires an active action");
                require(!uses.containsKey(id),"RESOURCE_ALREADY_USED","/mechanics","Resource effect already resolved in this period");capacity(uses.size()+1);
                uses.put(id,new Use(e.id(),key,period,machine.flow.round(),period.equals("ACTIVATION")?activationScope(machine.actor):scope));yield null;
            }
            case "effects.apply" -> RuleValue.text(applyEffect(args));
            case "effects.remove" -> { var effect=effects.get(text(args,"id"));require(effect!=null,"EFFECT_UNKNOWN","/mechanics","Effect is not active");expire(effect);yield null; }
            case "queues.plan","queues.commit","queues.reveal" -> queue(op,args);
            default -> query(op,args);
        };
    }
    private Roll roll(String id) { var roll=rolls.get(id);require(roll!=null,"ROLL_UNKNOWN","/mechanics","Roll does not exist");return roll; }
    private Roll modifiable(String id,boolean cancellationAllowed) {
        var roll=roll(id);require(roll.active()&&roll.scope().equals(scope)&&!scope.isEmpty(),"ROLL_CLOSED","/mechanics","Roll is outside the current procedure");
        require(cancellationAllowed||!roll.cancellationStarted(),"ROLL_TIMING","/mechanics","Pool changes must precede symbol cancellation");return roll;
    }
    private MechanicsRules.Die die(String type) { var die=rules.mechanics().dice().get(type);require(die!=null,"DIE_UNKNOWN","/mechanics","Die type is not defined");return die; }
    private List<Die> rollDice(List<String> types) { capacity(types.size());types.forEach(this::die);return types.stream().map(type->rollDie(type,0)).toList(); }
    private Die rollDie(String type,int rerolls) {
        var definition=die(type);int ticket=draw(definition.totalWeight()),index=0;
        while(ticket>=definition.faces().get(index).weight())ticket-=definition.faces().get(index++).weight();
        var face=definition.faces().get(index);return new Die(type,index,face.value(),face.symbols(),rerolls,false);
    }
    private List<Integer> indices(RuleValue input,Roll roll) {
        var result=new ArrayList<Integer>();for(var value:input.items()) { long index=value.integer();require(index>=0&&index<roll.dice().size()&&!roll.dice().get((int)index).removed(),"DIE_INDEX","/mechanics","Die index is not active");result.add((int)index); }
        require(new HashSet<>(result).size()==result.size(),"DIE_DUPLICATE","/mechanics","Select each die once");return result;
    }
    private ArrayList<String> symbols(Roll roll) {
        var result=new ArrayList<String>();for(var die:roll.dice())if(!die.removed())result.addAll(die.symbols());
        for(String symbol:roll.cancelled())require(result.remove(symbol),"ROLL_INVALID","/mechanics","Cancellation differs from rolled symbols");return result;
    }
    private void saveDice(Roll roll,List<Die> dice,String event) { var next=new Roll(roll.id(),roll.owner(),roll.visibility(),roll.scope(),dice,roll.cancelled(),true,roll.cancellationStarted());rolls.put(next.id(),next);rollEvent(event,next); }
    private void close(Roll roll) { rolls.put(roll.id(),new Roll(roll.id(),roll.owner(),roll.visibility(),roll.scope(),roll.dice(),roll.cancelled(),false,roll.cancellationStarted())); }
    private void rollEvent(String name,Roll roll) {
        event(name,roll.visibility(),roll.owner(),Map.of("roll",RuleValue.text(roll.id()),"faces",integers(roll.dice().stream().filter(d->!d.removed()).map(d->(long)d.face()).toList()),"symbols",strings(symbols(roll))));
    }
    private Pile pile(String id) { var pile=piles.get(id);require(pile!=null,"PILE_UNKNOWN","/mechanics","Card pile does not exist");return pile; }
    private Pile deck(String id) { var pile=pile(id);require(pile.kind()==PileKind.DECK,"DECK_REQUIRED","/mechanics","Expected draw deck");return pile; }
    private String createDeck(Map<String,RuleValue> args) {
        String key=text(args,"deck");var definition=rules.mechanics().decks().get(key);require(definition!=null,"DECK_UNKNOWN","/mechanics","Deck type is not defined");
        capacity(cards.size()+definition.cards().size());capacity(piles.size()+2);String id=id("deck-"),owner=owner(args);var ids=new ArrayList<String>();
        for(String type:definition.cards()) { String card=id("card-");ids.add(card);cards.put(card,new Card(card,type,id)); }
        piles.put(id,new Pile(id,PileKind.DECK,id,key,owner,audience(text(args,"visibility")),definition.shuffle()?shuffled(ids):ids));
        piles.put(id+":discard",new Pile(id+":discard",PileKind.DISCARD,id,key,owner,definition.discardVisibility(),List.of()));return id;
    }
    private List<String> shuffled(List<String> input) { var copy=new ArrayList<>(input);for(int i=copy.size()-1;i>0;i--)Collections.swap(copy,i,draw(i+1));return copy; }
    private RuleValue drawCards(Map<String,RuleValue> args) {
        var deck=deck(text(args,"deck"));var to=pile(text(args,"to"));require(to.kind()==PileKind.HAND,"HAND_REQUIRED","/mechanics","Draw destination must be a hand");
        long requested=number(args,"count");require(requested>=0&&requested<=maximum(),"DRAW_COUNT","/mechanics","Invalid draw count");
        var policy=rules.mechanics().decks().get(deck.definition());var discard=pile(deck.id()+":discard");int available=deck.cards().size()+(policy.exhaustion()==MechanicsRules.Exhaustion.RESHUFFLE?discard.cards().size():0);
        if(policy.exhaustion()!=MechanicsRules.Exhaustion.PARTIAL)require(requested<=available,"DECK_EXHAUSTED","/mechanics","Not enough cards");
        int count=(int)Math.min(requested,available);capacity(to.cards().size()+count);var remaining=new ArrayList<>(deck.cards());var drawn=new ArrayList<String>();
        for(int i=0;i<count;i++) {
            if(remaining.isEmpty()) { remaining.addAll(shuffled(discard.cards()));discard=discard.withCards(List.of()); }
            drawn.add(remaining.removeFirst());
        }
        var hand=new ArrayList<>(to.cards());hand.addAll(drawn);piles.put(deck.id(),deck.withCards(remaining));piles.put(discard.id(),discard);piles.put(to.id(),to.withCards(hand));
        event("cardsDrawn",to.visibility(),to.owner(),Map.of("pile",RuleValue.text(to.id()),"cards",strings(drawn)));return strings(drawn);
    }
    private void moveCards(String operation,Map<String,RuleValue> args) {
        var from=pile(text(args,"from"));var to=operation.equals("cards.discard")?pile(deck(text(args,"deck")).id()+":discard"):pile(text(args,"to"));
        require(from.kind()==PileKind.HAND&&(operation.equals("cards.discard")||to.kind()==PileKind.HAND)&&!from.id().equals(to.id()),"CARD_MOVE","/mechanics","Invalid card movement");
        var selected=strings(args.get("cards"));require(new HashSet<>(selected).size()==selected.size()&&from.cards().containsAll(selected),"CARD_NOT_HELD","/mechanics","Card is not held in that pile");
        if(operation.equals("cards.discard"))for(String id:selected)require(cards.get(id).deck().equals(to.deck()),"CARD_ORIGIN","/mechanics","Card belongs to a different deck");
        capacity(to.cards().size()+selected.size());var source=new ArrayList<>(from.cards());source.removeAll(selected);var target=new ArrayList<>(to.cards());target.addAll(selected);
        piles.put(from.id(),from.withCards(source));piles.put(to.id(),to.withCards(target));event("cardsMoved",to.visibility(),to.owner(),Map.of("pile",RuleValue.text(to.id()),"cards",strings(selected)));
    }
    private void setResource(EvaluationContext.Entity entity,String name,long value) {
        var definition=rules.entities().get(entity.type()).resources().get(name);require(definition!=null,"RESOURCE_UNKNOWN","/mechanics","Unknown resource");resource(definition,value,"/mechanics");
        var values=new TreeMap<>(machine.entities.get(entity.id()).resources());values.put(name,value);
        machine.entities.put(entity.id(),new EvaluationContext.Entity(entity.type(),entity.id(),entity.owner(),entity.attributes(),values));machine.system("resourceChanged","/mechanics");
    }
    private long amount(EvaluationContext.Entity entity,String name) { require(entity.resources().containsKey(name),"RESOURCE_UNKNOWN","/mechanics","Unknown resource");return entity.resources().get(name); }
    private void changeResources(EvaluationContext.Entity from,String a,long deltaA,EvaluationContext.Entity to,String b,long deltaB) {
        long left=Math.addExact(amount(from,a),deltaA),right=Math.addExact(amount(to,b),deltaB);
        // All work is isolated until the enclosing command commits.
        setResource(from,a,left);setResource(to,b,right);
    }
    private String period(Map<String,RuleValue> args) {
        String period=text(args,"period");require(Set.of("ROUND","ACTION","ACTIVATION").contains(period),"PERIOD_INVALID","/mechanics","Unknown usage period");return period;
    }
    private static String useKey(String entity,String key,String period) { return entity.length()+":"+entity+":"+key+":"+period; }
    private MechanicsRules.Effect effect(String key) { var policy=rules.mechanics().effects().get(key);require(policy!=null,"EFFECT_UNKNOWN","/mechanics","Effect type is not defined");return policy; }
    private List<ActiveEffect> matching(String entity,String key) { return effects.values().stream().filter(e->e.entity().equals(entity)&&e.key().equals(key)).toList(); }
    private String applyEffect(Map<String,RuleValue> args) {
        var target=entity(args.get("entity"));String key=text(args,"key");var policy=effect(key);
        require(policy.visibility()!=CompiledRules.Audience.OWNER||target.owner()!=null,"EFFECT_OWNER","/mechanics","Owner effect requires a player-owned target");
        if(policy.duration()==MechanicsRules.Duration.ACTIVATION||policy.duration()==MechanicsRules.Duration.ACTION)require(machine.purpose==RulesState.Purpose.ACTION,"EFFECT_SCOPE","/mechanics","Scoped effect requires an active action");
        var existing=matching(target.id(),key);
        if(policy.stacking()==MechanicsRules.Stacking.REPLACE)existing.forEach(this::expire);
        else require(existing.size()<policy.maximumStacks(),"EFFECT_STACK_LIMIT","/mechanics","Effect stack limit exceeded");
        capacity(effects.size()+1);String id=id("effect-");var effect=new ActiveEffect(id,target.id(),key,number(args,"value"),target.owner(),policy.visibility(),machine.flow.round(),policy.duration()==MechanicsRules.Duration.ACTIVATION?activationScope(machine.actor):scope);effects.put(id,effect);
        total(target.id(),key);event("effectApplied",effect.visibility(),effect.owner(),Map.of("effect",RuleValue.text(id),"key",RuleValue.text(key),"value",RuleValue.integer(effect.value())));return id;
    }
    private void expire(ActiveEffect effect) { effects.remove(effect.id());event("effectExpired",effect.visibility(),effect.owner(),Map.of("effect",RuleValue.text(effect.id()))); }
    private long total(String entity,String key) {
        var policy=effect(key);var values=matching(entity,key);long total=0;
        if(policy.stacking()==MechanicsRules.Stacking.MAX)return values.stream().mapToLong(ActiveEffect::value).max().orElse(0);
        for(var value:values) { tick();total=Math.addExact(total,value.value()); }return total;
    }
    private RuleValue queue(String operation,Map<String,RuleValue> args) {
        var entity=entity(args.get("entity"));String field=text(args,"field");var schema=rules.entities().get(entity.type()).attributes().get(field);
        require(schema!=null&&schema.type().equals(RuleType.list(RuleType.STRING)),"QUEUE_FIELD","/mechanics","Queue must be a declared string list");
        require(entity.attributes().containsKey(field),"QUEUE_FIELD","/mechanics","Queue is unavailable in this view");
        String key=entity.id().length()+":"+entity.id()+":"+field;var values=new ArrayList<>(strings(entity.attributes().get(field)));var previous=queues.get(key);int locked=previous==null?0:previous.locked();
        require(locked<=values.size(),"QUEUE_INVALID","/mechanics","Committed queue prefix is missing");
        RuleValue result=null;
        if(operation.equals("queues.plan")) {
            long capacity=number(args,"capacity");var orders=strings(args.get("orders"));var allowed=strings(args.get("allowed"));
            require(capacity>=locked&&capacity<=maximum()&&orders.size()==capacity-locked,"QUEUE_CAPACITY","/mechanics","Fill exactly the available suffix");
            require(allowed.containsAll(orders),"ORDER_UNKNOWN","/mechanics","Order is not in the permitted set");values=new ArrayList<>(values.subList(0,locked));values.addAll(orders);
        } else if(operation.equals("queues.commit"))locked=values.size();
        else { require(locked>0,"QUEUE_EMPTY","/mechanics","No committed order is available");result=RuleValue.text(values.removeFirst());locked--; }
        if(!queues.containsKey(key))capacity(queues.size()+1);queues.put(key,new QueueLock(entity.id(),field,locked));
        var attributes=new TreeMap<>(entity.attributes());attributes.put(field,strings(values));machine.entities.put(entity.id(),new EvaluationContext.Entity(entity.type(),entity.id(),entity.owner(),attributes,entity.resources()));
        event("queueChanged",schema.visibility(),entity.owner(),Map.of("entity",RuleValue.entity(entity.type(),entity.id())));return result;
    }
    RuleValue query(String op,Map<String,RuleValue> args) {
        tick();
        if(machine.previewMode&&op.startsWith("effects.")) {
            var target=entity(args.get("entity"));var policy=effect(text(args,"key"));
            require(policy.visibility()==CompiledRules.Audience.PUBLIC||policy.visibility()==CompiledRules.Audience.OWNER&&Objects.equals(target.owner(),machine.actor),"QUERY_NOT_VISIBLE","/mechanics","Effect query is unavailable in this view");
        }
        if(machine.previewMode&&op.equals("resources.used"))require(Objects.equals(entity(args.get("entity")).owner(),machine.actor),"QUERY_NOT_VISIBLE","/mechanics","Usage query is unavailable in this view");
        return switch(op) {
            case "dice.indices" -> {
                var roll=roll(text(args,"roll"));var values=new ArrayList<Long>();for(int i=0;i<roll.dice().size();i++)if(!roll.dice().get(i).removed())values.add((long)i);yield integers(values);
            }
            case "dice.symbols" -> strings(symbols(roll(text(args,"roll"))));
            case "dice.value","dice.symbolsAt","dice.type" -> {
                var roll=roll(text(args,"roll"));var index=indices(RuleValue.list(RuleType.INTEGER,List.of(args.get("index"))),roll).getFirst();var die=roll.dice().get(index);
                yield op.equals("dice.value")?RuleValue.integer(die.value()):op.equals("dice.type")?RuleValue.text(die.type()):strings(die.symbols());
            }
            case "dice.resolve" -> {
                var roll=roll(text(args,"roll"));var table=rules.mechanics().resultTables().get(text(args,"table"));require(table!=null,"RESULT_TABLE_UNKNOWN","/mechanics","Result table is not defined");long value=0;
                for(var die:roll.dice())if(!die.removed()) { tick();value=Math.addExact(value,Math.multiplyExact(die.value(),table.valueMultiplier())); }
                for(String symbol:symbols(roll)) { tick();require(table.ignoreUnmapped()||table.symbols().containsKey(symbol),"SYMBOL_UNMAPPED","/mechanics","Symbol has no result mapping");value=Math.addExact(value,table.symbols().getOrDefault(symbol,0L)); }
                yield RuleValue.integer(value);
            }
            case "cards.pile" -> strings(pile(text(args,"pile")).cards());
            case "cards.type" -> { var card=cards.get(text(args,"card"));require(card!=null,"CARD_UNKNOWN","/mechanics","Card does not exist");yield RuleValue.text(card.type()); }
            case "effects.total" -> RuleValue.integer(total(entity(args.get("entity")).id(),text(args,"key")));
            case "effects.ids" -> { var target=entity(args.get("entity"));effect(text(args,"key"));yield strings(matching(target.id(),text(args,"key")).stream().map(ActiveEffect::id).toList()); }
            case "resources.used" -> { var target=entity(args.get("entity"));yield RuleValue.bool(uses.containsKey(useKey(target.id(),text(args,"key"),period(args)))); }
            default -> throw new RuleEvaluationException("OPERATION_UNAVAILABLE","/mechanics","Mechanical handler is unavailable");
        };
    }
}
