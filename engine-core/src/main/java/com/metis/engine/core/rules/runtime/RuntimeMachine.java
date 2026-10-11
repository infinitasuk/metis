package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;

import static com.metis.engine.core.rules.runtime.RuntimeChecks.*;
import static com.metis.engine.core.rules.runtime.RulesState.*;

/** One isolated transition. It is discarded in its entirety on rejection. */
final class RuntimeMachine {
    final RulesRuntime runtime;
    final CompiledRules rules;
    final RulesState before;
    final Map<String,RuleValue> globals;
    final Map<String,EvaluationContext.Entity> entities;
    final EvaluationContext.Budget budget;
    final List<Event> events=new ArrayList<>();
    final List<Trace> trace=new ArrayList<>();
    final List<Frame> frames=new ArrayList<>();
    Status status;
    Flow flow;
    Decision decision;
    Purpose purpose;
    String actor,action,outcome;
    int actionStep;
    List<String> winners;
    long nextDecision;
    String pendingPhase;
    int pendingRound;
    final MechanicsMachine mechanics;
    final SpatialMachine spatial;
    final ManeuverMachine maneuver;
    final ScenarioMachine scenario;
    final boolean previewMode;
    final List<SpatialMachine.Plan> movements=new ArrayList<>();

    RuntimeMachine(RulesRuntime runtime,RulesState before,boolean resume) {
        this(runtime,before,resume,false);
    }
    RuntimeMachine(RulesRuntime runtime,RulesState before,boolean resume,boolean previewMode) {
        this.previewMode=previewMode;
        this.runtime=runtime;this.rules=runtime.definition();this.before=before;
        globals=new TreeMap<>(before.globals());entities=new TreeMap<>(before.entities());
        var control=before.control();status=control.status();flow=control.flow();decision=control.decision();outcome=control.outcome();winners=control.winners();nextDecision=control.nextDecision();
        var continuation=resume?control.continuation():null;
        int remaining=continuation==null?rules.limits().maxInstructions():continuation.remainingBudget();
        require(remaining>0,"EVALUATION_LIMIT","","Continuation has no remaining execution budget");
        budget=new EvaluationContext.Budget(remaining);
        mechanics=control.mechanics()==null?null:new MechanicsMachine(this,control.mechanics());
        spatial=new SpatialMachine(this);maneuver=new ManeuverMachine(this);scenario=new ScenarioMachine(this);
        if(continuation!=null) { purpose=continuation.purpose();actor=continuation.actor();action=continuation.action();actionStep=continuation.actionStep();frames.addAll(continuation.frames()); }
    }
    EvaluationContext context(Map<String,RuleValue> locals) {
        var variables=new TreeMap<>(locals);variables.put("round",RuleValue.integer(flow.round()));
        variables.put("players",RuleValue.list(RuleType.PLAYER,before.players().stream().map(RuleValue::player).toList()));
        var queries=new TreeMap<>(runtime.queries());if(mechanics!=null)queries.putAll(mechanics.queries());
        queries.putAll(spatial.queries());
        queries.putAll(maneuver.queries());
        return new EvaluationContext(variables,globals,entities,rules.limits().maxCollectionSize(),budget,queries);
    }
    RuleValue evaluate(Expression expression,Map<String,RuleValue> locals) { return Expressions.evaluate(expression,context(locals)); }
    RuleValue checked(RuleValue value,RuleType type,String path,boolean refs) { return RuntimeChecks.value(value,type,rules,before.players(),entities,budget,path,refs); }
    Map<String,RuleValue> arguments(Map<String,Expression> expressions,Map<String,RuleType> signature,Map<String,RuleValue> locals,String path) {
        var result=new TreeMap<String,RuleValue>();
        for(var entry:expressions.entrySet())result.put(entry.getKey(),checked(evaluate(entry.getValue(),locals),signature.get(entry.getKey()),path+"/"+entry.getKey(),true));
        return result;
    }
    void begin(Purpose purpose,String procedure,Map<String,RuleValue> arguments,String actor,String action,int step) {
        this.purpose=purpose;this.actor=actor;this.action=action;this.actionStep=step;
        if(mechanics!=null)mechanics.beginScope(purpose);
        pushBlock("/procedures/"+procedure+"/steps",arguments,1);
    }
    private void pushBlock(String path,Map<String,RuleValue> locals,int depth) {
        require(depth<=rules.limits().maxCallDepth(),"CALL_DEPTH",path,"Procedure call depth exceeded");
        frames.add(new Frame(FrameKind.BLOCK,path,0,locals,List.of(),null,depth));
    }
    private void bind(String name,RuleValue value) { int index=frames.size()-1;frames.set(index,frames.get(index).bind(name,value)); }
    void event(String type,CompiledRules.Audience visibility,String recipient,Map<String,RuleValue> values,String path) {
        require(before.events().size()+events.size()<100000,"EVENT_LIMIT",path,"Session event limit reached");
        events.add(new Event((long)before.events().size()+events.size()+1,type,actor,visibility,recipient,values,path));
    }
    void system(String type,String path) { event("rules."+type,CompiledRules.Audience.SERVER,null,Map.of(),path); }
    void run() {
        while(decision==null) {
            if(status==Status.FINISHED) {
                if(mechanics!=null)mechanics.finishScope();
                if(purpose==Purpose.ACTION)creditAction(false);
                frames.clear();purpose=null;break;
            }
            if(pendingPhase!=null) { String next=pendingPhase;pendingPhase=null;enterPhase(next,pendingRound);continue; }
            if(frames.isEmpty()) {
                if(purpose==null)break;
                Purpose finished=purpose;if(mechanics!=null)mechanics.finishScope();purpose=null;
                if(finished==Purpose.SETUP) { status=Status.RUNNING;enterPhase(rules.flow().initial(),rules.flow().initial().equals(rules.flow().roundStart())?1:0); }
                else if(finished==Purpose.ENTRY)readyPhase();
                else creditAction(true);
                continue;
            }
            int top=frames.size()-1;Frame frame=frames.get(top);
            if(frame.kind()==FrameKind.WINDOW) { window(frame);continue; }
            if(frame.kind()==FrameKind.LOOP) {
                if(frame.index()==frame.items().size()) { frames.remove(top);continue; }
                budget.spend(frame.block());frames.set(top,frame.at(frame.index()+1));
                var locals=new TreeMap<>(frame.locals());locals.put(frame.binding(),frame.items().get(frame.index()));
                pushBlock(frame.block(),locals,frame.callDepth());continue;
            }
            var instructions=runtime.block(frame.block());
            if(frame.index()==instructions.size()) { frames.remove(top);continue; }
            var instruction=instructions.get(frame.index());budget.spend(instruction.path());
            frames.set(top,frame.at(frame.index()+1));trace.add(new Trace(instruction.operation(),instruction.path(),actor));
            execute(instruction,frame.locals(),frame.callDepth());
        }
    }
    private void execute(CompiledRules.Instruction instruction,Map<String,RuleValue> locals,int depth) {
        var refs=instruction.references();String path=instruction.path();
        if(previewMode) {
            var effects=runtime.operation(instruction.operation()).effects();
            require(!effects.contains(OperationRegistry.Effect.RANDOM),"PREVIEW_RANDOM_REQUIRED",path,"Preview cannot execute randomness");
            require(!effects.contains(OperationRegistry.Effect.DECISION),"PREVIEW_DECISION_REQUIRED",path,"Preview cannot answer a decision");
        }
        switch(instruction.operation()) {
            case "core.let" -> bind(instruction.binding(),input(instruction,"value",locals));
            case "core.if" -> pushBlock(path+(input(instruction,"condition",locals).bool()?"/then":"/else"),locals,depth);
            case "core.forEach" -> {
                var items=input(instruction,"source",locals).items();
                require(items.size()<=Integer.parseInt(refs.get("maxItems")),"LOOP_BOUND",path,"Source exceeds explicit iteration bound");
                frames.add(new Frame(FrameKind.LOOP,path+"/steps",0,locals,items,instruction.binding(),depth));
            }
            case "core.call" -> {
                String procedure=refs.get("procedure");var args=arguments(instruction.inputs(),rules.procedures().get(procedure).parameters(),locals,path);
                pushBlock("/procedures/"+procedure+"/steps",args,depth+1);
            }
            case "core.window" -> {
                var window=rules.windows().get(refs.get("window"));var args=arguments(instruction.inputs(),window.parameters(),locals,path);
                var responders=evaluate(window.responders(),args);checked(responders,RuleType.list(RuleType.PLAYER),path,true);
                require(responders.items().size()<=window.maxResponders()&&new HashSet<>(responders.items()).size()==responders.items().size(),"WINDOW_RESPONDERS",path,"Responder list must be bounded and unique");
                require(frames.stream().filter(f->f.kind()==FrameKind.WINDOW).count()<rules.limits().maxWindowDepth(),"WINDOW_DEPTH",path,"Reaction nesting limit exceeded");
                frames.add(new Frame(FrameKind.WINDOW,"/windows/"+refs.get("window"),0,args,responders.items(),null,depth));system("windowOpened",path);
            }
            case "core.choose" -> {
                String player=(String)checked(input(instruction,"player",locals),RuleType.PLAYER,path,true).value();
                var options=input(instruction,"options",locals);
                require(!options.items().isEmpty(),"DECISION_EMPTY",path,"Required decision has no options");checked(options,options.type(),path,true);
                var visibility=CompiledRules.Audience.valueOf(refs.get("visibility"));
                String decisionId=before.sessionId()+":"+nextDecision;nextDecision=Math.incrementExact(nextDecision);
                decision=new Decision(decisionId,player,instruction.binding(),visibility,options.items(),path);
                event("rules.decisionOpened",visibility,visibility==CompiledRules.Audience.OWNER?player:null,Map.of("decisionId",RuleValue.text(decision.id())),path);
            }
            case "core.setState" -> {
                String field=refs.get("field");globals.put(field,checked(input(instruction,"value",locals),rules.state().get(field).type(),path,true));system("stateChanged",path);
            }
            case "core.setAttribute" -> {
                var entity=entity(input(instruction,"entity",locals),path);String field=refs.get("field");
                var attributes=new TreeMap<>(entity.attributes());attributes.put(field,checked(input(instruction,"value",locals),rules.entities().get(entity.type()).attributes().get(field).type(),path,true));
                entities.put(entity.id(),new EvaluationContext.Entity(entity.type(),entity.id(),entity.owner(),attributes,entity.resources()));system("attributeChanged",path);
            }
            case "core.modifyResource" -> {
                var entity=entity(input(instruction,"entity",locals),path);String resource=refs.get("resource");
                require(entity.resources().containsKey(resource),"RESOURCE_UNAVAILABLE",path,"Resource is unavailable in this view");
                long amount=Math.addExact(entity.resources().get(resource),input(instruction,"delta",locals).integer());
                resource(rules.entities().get(entity.type()).resources().get(resource),amount,path);
                var resources=new TreeMap<>(entity.resources());resources.put(resource,amount);
                entities.put(entity.id(),new EvaluationContext.Entity(entity.type(),entity.id(),entity.owner(),entity.attributes(),resources));system("resourceChanged",path);
            }
            case "core.createEntity" -> create(instruction,locals);
            case "core.removeEntity" -> { var entity=entity(input(instruction,"entity",locals),path);entities.remove(entity.id());if(mechanics!=null)mechanics.entityRemoved(entity.id());system("entityRemoved",path); }
            case "core.emit" -> {
                String event=refs.get("event");var declaration=rules.events().get(event);var values=new TreeMap<String,RuleValue>();
                for(var field:declaration.fields().entrySet())values.put(field.getKey(),checked(input(instruction,"value:"+field.getKey(),locals),field.getValue(),path,true));
                String recipient=instruction.inputs().containsKey("to")?(String)checked(input(instruction,"to",locals),RuleType.PLAYER,path,true).value():null;
                event("game."+event,declaration.visibility(),recipient,values,path);
            }
            case "core.endGame" -> {
                var winnersValue=input(instruction,"winners",locals);checked(winnersValue,RuleType.list(RuleType.PLAYER),path,true);
                var selected=winnersValue.items().stream().map(value->(String)value.value()).toList();
                require(new HashSet<>(selected).size()==selected.size(),"WINNER_DUPLICATE",path,"Winner list contains duplicates");
                status=Status.FINISHED;outcome=(String)input(instruction,"outcome",locals).value();winners=selected;
                event("rules.gameEnded",CompiledRules.Audience.PUBLIC,null,Map.of("outcome",RuleValue.text(outcome),"winners",winnersValue),path);
            }
            default -> {
                var contract=runtime.operation(instruction.operation());
                var args=arguments(instruction.inputs(),contract.arguments(),locals,path);RuleValue value;
                if(instruction.operation().startsWith("space."))value=spatial.execute(instruction.operation(),args);
                else if(instruction.operation().startsWith("maneuver."))value=maneuver.execute(instruction.operation(),args);
                else if(instruction.operation().startsWith("scenario."))value=scenario.execute(instruction.operation(),args);
                else { require(mechanics!=null,"OPERATION_UNAVAILABLE",path,"Mechanical state is unavailable");value=mechanics.execute(instruction.operation(),args); }
                if(instruction.binding()!=null)bind(instruction.binding(),checked(value,contract.result(),path,true));
            }
        }
    }
    private RuleValue input(CompiledRules.Instruction instruction,String name,Map<String,RuleValue> locals) { return evaluate(instruction.inputs().get(name),locals); }
    private EvaluationContext.Entity entity(RuleValue value,String path) {
        var ref=(RuleValue.EntityRef)checked(value,value.type(),path,true).value();return entities.get(ref.id());
    }
    private void create(CompiledRules.Instruction instruction,Map<String,RuleValue> locals) {
        String path=instruction.path(),type=instruction.references().get("type"),id=(String)input(instruction,"id",locals).value();var declaration=rules.entities().get(type);
        require(!id.isBlank()&&!entities.containsKey(id),"ENTITY_ID",path,"Entity ID must be nonempty and unused");
        require(entities.size()<rules.limits().maxCollectionSize(),"COLLECTION_LIMIT",path,"Entity capacity exceeded");
        String owner=instruction.inputs().containsKey("owner")?(String)checked(input(instruction,"owner",locals),RuleType.PLAYER,path,true).value():null;
        var attributes=new TreeMap<String,RuleValue>();
        declaration.attributes().forEach((name,field)->attributes.put(name,checked(instruction.inputs().containsKey("attribute:"+name)?input(instruction,"attribute:"+name,locals):field.initial(),field.type(),path,false)));
        var resources=new TreeMap<String,Long>();
        declaration.resources().forEach((name,field)->{
            long amount=instruction.inputs().containsKey("resource:"+name)?input(instruction,"resource:"+name,locals).integer():field.initial();resource(field,amount,path);resources.put(name,amount);
        });
        entities.put(id,new EvaluationContext.Entity(type,id,owner,attributes,resources));bind(instruction.binding(),RuleValue.entity(type,id));system("entityCreated",path);
    }
    void respond(String player,String decisionId,int option) {
        require(decision!=null,"NO_DECISION","","No decision is pending");
        require(decision.player().equals(player),"WRONG_RESPONDER",decision.path(),"Only the designated player can respond");
        require(decision.id().equals(decisionId),"STALE_DECISION",decision.path(),"Decision identifier does not match");
        require(option>=0&&option<decision.options().size(),"CHOICE_INVALID",decision.path(),"Choose an offered option");
        var value=decision.options().get(option);checked(value,value.type(),decision.path(),true);
        var frame=frames.getLast();
        if(frame.kind()==FrameKind.WINDOW)respondWindow(frame,player,(String)value.value());else bind(decision.binding(),value);
        String originalActor=actor;actor=player;
        event("rules.decisionResolved",decision.visibility(),decision.visibility()==CompiledRules.Audience.OWNER?player:null,Map.of("choice",value),decision.path());
        actor=originalActor;
        trace.add(new Trace("core.choose:resolved",decision.path(),player));decision=null;run();
    }
    private CompiledRules.Window declaration(Frame frame) { return rules.windows().get(frame.block().substring("/windows/".length())); }
    private Map<String,RuleValue> responderScope(Frame frame,String player) { var scope=new TreeMap<>(frame.locals());scope.put("responder",RuleValue.player(player));return scope; }
    private void window(Frame frame) {
        budget.spend(frame.block());int top=frames.size()-1;
        if(frame.index()==frame.items().size()) { frames.remove(top);system("windowClosed",frame.block());return; }
        var window=declaration(frame);String player=(String)frame.items().get(frame.index()).value();var scope=responderScope(frame,player);
        var choices=new ArrayList<RuleValue>();
        for(var choice:window.choices())if(evaluate(choice.condition(),scope).bool())choices.add(RuleValue.text(choice.id()));
        if(choices.isEmpty()) { frames.set(top,frame.at(frame.index()+1));return; }
        if(window.allowPass())choices.add(RuleValue.text("$pass"));
        String id=before.sessionId()+":"+nextDecision;nextDecision=Math.incrementExact(nextDecision);
        decision=new Decision(id,player,"@window",window.visibility(),choices,frame.block());
        event("rules.decisionOpened",window.visibility(),window.visibility()==CompiledRules.Audience.OWNER?player:null,Map.of("decisionId",RuleValue.text(id)),frame.block());
    }
    private void respondWindow(Frame frame,String player,String selected) {
        var window=declaration(frame);var scope=responderScope(frame,player);
        CompiledRules.Reaction reaction=null;
        if(selected.equals("$pass"))require(window.allowPass(),"REACTION_PASS",frame.block(),"Passing is not allowed");
        else {
            reaction=window.choices().stream().filter(c->c.id().equals(selected)).findFirst().orElseThrow();
            require(evaluate(reaction.condition(),scope).bool(),"REACTION_INELIGIBLE",frame.block(),"Reaction is no longer eligible");
        }
        frames.set(frames.size()-1,frame.at(frame.index()+1));
        if(reaction!=null)pushBlock("/procedures/"+reaction.procedure()+"/steps",arguments(reaction.arguments(),rules.procedures().get(reaction.procedure()).parameters(),scope,frame.block()),frame.callDepth()+1);
    }
    CompiledRules.Phase phase() { return rules.flow().phases().get(flow.phase()); }
    boolean entityPhase() { return phase().entities()!=null; }
    boolean sharedPhase() { return phase().controller()==CompiledRules.Controller.MANUAL||phase().controller()==CompiledRules.Controller.SIMULTANEOUS; }
    Progress progress(String player) { return flow.progress().getOrDefault(player,Progress.empty()); }
    void actorAllowed(String player) {
        require(player!=null&&before.players().contains(player),"PLAYER_UNKNOWN","","Unknown player");
        require(sharedPhase()?!flow.completedPlayers().contains(player):flow.playerIndex()>=0&&before.players().get(flow.playerIndex()).equals(player),"NOT_ACTIVE_PLAYER","","Player cannot act now");
    }
    List<String> eligible(String player) {
        var values=evaluate(phase().entities(),Map.of("actor",RuleValue.player(player))).items();var result=new ArrayList<String>();var seen=new HashSet<String>();
        for(var value:values) {
            var ref=(RuleValue.EntityRef)checked(value,value.type(),"/flow",true).value();require(seen.add(ref.id()),"ELIGIBILITY_DUPLICATE","/flow","Entity selector contains duplicates");
            if(!flow.completedEntities().contains(ref.id()))result.add(ref.id());
        }
        return result;
    }
    void activate(String player,String entity) {
        actorAllowed(player);require(entityPhase(),"ENTITY_PHASE_REQUIRED","","This phase has no entity activation");
        require(flow.entity()==null,"ACTIVATION_IN_PROGRESS","","Complete the active entity first");
        require(eligible(player).contains(entity),"ENTITY_INELIGIBLE","","Entity is not eligible for this activation");
        var progress=new TreeMap<>(flow.progress());progress.put(player,Progress.empty());
        flow=new Flow(flow.phase(),flow.round(),flow.playerIndex(),entity,flow.groupCompleted(),progress,flow.completedPlayers(),flow.completedEntities());
        actor=player;system("entityActivated","/flow");actor=null;
    }
    int actionStep(String player,String action) {
        var progress=progress(player);var steps=phase().steps();
        for(int i=progress.step();i<steps.size();i++) {
            var step=steps.get(i);int used=progress.uses().getOrDefault(step.id(),0);
            if(step.actions().contains(action)&&used<step.maximum())return i;
            if(used<step.minimum())break;
        }
        throw new RuleEvaluationException("ACTION_BUDGET","/flow","Action is not available in the current activation steps");
    }
    boolean minimumsMet(String player) { return phase().steps().stream().allMatch(step->progress(player).uses().getOrDefault(step.id(),0)>=step.minimum()); }
    void pass(String player) {
        actorAllowed(player);require(!entityPhase()||flow.entity()!=null,"ENTITY_REQUIRED","","Select an entity first");
        require(minimumsMet(player),"STEP_REQUIRED","","Required actions remain");actor=player;finishActor(player);run();
    }
    void advanceStep(String player) {
        actorAllowed(player);require(!entityPhase()||flow.entity()!=null,"ENTITY_REQUIRED","","Select an entity first");
        var progress=progress(player);var steps=phase().steps();require(progress.step()+1<steps.size(),"STEP_UNAVAILABLE","","No later activation step");
        var step=steps.get(progress.step());require(progress.uses().getOrDefault(step.id(),0)>=step.minimum(),"STEP_REQUIRED","","Required actions remain");
        setProgress(player,new Progress(progress.step()+1,progress.uses()));actor=player;system("stepAdvanced","/flow");actor=null;
    }
    private void setProgress(String player,Progress progress) {
        var all=new TreeMap<>(flow.progress());all.put(player,progress);
        flow=new Flow(flow.phase(),flow.round(),flow.playerIndex(),flow.entity(),flow.groupCompleted(),all,flow.completedPlayers(),flow.completedEntities());
    }
    private void creditAction(boolean advance) {
        var progress=progress(actor);var steps=phase().steps();var uses=new TreeMap<>(progress.uses());var step=steps.get(actionStep);
        uses.merge(step.id(),1,Integer::sum);int index=actionStep;
        while(index<steps.size()&&uses.getOrDefault(steps.get(index).id(),0)>=steps.get(index).maximum())index++;
        setProgress(actor,new Progress(index,uses));system("actionCompleted","/actions/"+action);
        if(advance && (index==steps.size() || entityPhase()&&!eligible(actor).contains(flow.entity())))finishActor(actor);
    }
    private void finishActor(String player) {
        budget.spend("/flow");if(mechanics!=null)mechanics.finishActivation(player);system("activationCompleted","/flow");
        if(entityPhase()) {
            var completed=new HashSet<>(flow.completedEntities());completed.add(flow.entity());
            flow=new Flow(flow.phase(),flow.round(),flow.playerIndex(),null,Math.incrementExact(flow.groupCompleted()),flow.progress(),flow.completedPlayers(),completed);
            if(!eligible(player).isEmpty()&&(phase().controller()==CompiledRules.Controller.PLAYER_PHASE||flow.groupCompleted()<phase().groupSize()))return;
            if(phase().controller()==CompiledRules.Controller.PLAYER_PHASE) {
                var done=new HashSet<>(flow.completedPlayers());done.add(player);flow=new Flow(flow.phase(),flow.round(),flow.playerIndex(),null,0,flow.progress(),done,flow.completedEntities());
            }
            selectEntityPlayer(flow.playerIndex());return;
        }
        var done=new HashSet<>(flow.completedPlayers());done.add(player);
        flow=new Flow(flow.phase(),flow.round(),flow.playerIndex(),null,0,flow.progress(),done,flow.completedEntities());
        if(done.size()==before.players().size()) { nextPhase();return; }
        if(!sharedPhase())for(int offset=1;offset<=before.players().size();offset++) {
            int index=(flow.playerIndex()+offset)%before.players().size();if(!done.contains(before.players().get(index))) {
                flow=new Flow(flow.phase(),flow.round(),index,null,0,flow.progress(),done,flow.completedEntities());return;
            }
        }
    }
    private void selectEntityPlayer(int previous) {
        for(int offset=1;offset<=before.players().size();offset++) {
            int index=(previous+offset)%before.players().size();String player=before.players().get(index);
            if(!flow.completedPlayers().contains(player)&&!eligible(player).isEmpty()) {
                flow=new Flow(flow.phase(),flow.round(),index,null,0,flow.progress(),flow.completedPlayers(),flow.completedEntities());return;
            }
        }
        nextPhase();
    }
    private void enterPhase(String name,int round) {
        budget.spend("/flow");if(mechanics!=null&&round!=flow.round())mechanics.roundChanged(round);
        flow=new Flow(name,round,-1,null,0,Map.of(),Set.of(),Set.of());system("phaseEntered","/flow/phases/"+name);
        if(phase().onEnter()!=null)begin(Purpose.ENTRY,phase().onEnter(),Map.of(),null,null,-1);else readyPhase();
    }
    private void readyPhase() {
        if(phase().controller()==CompiledRules.Controller.AUTOMATIC) { nextPhase();return; }
        if(entityPhase())selectEntityPlayer(-1);
        else if(!sharedPhase())flow=new Flow(flow.phase(),flow.round(),0,null,0,flow.progress(),flow.completedPlayers(),flow.completedEntities());
    }
    private void nextPhase() {
        pendingPhase=phase().next();pendingRound=pendingPhase.equals(rules.flow().roundStart())?Math.incrementExact(flow.round()):flow.round();
    }
    Result commit() {
        var continuation=purpose==null?null:new Continuation(purpose,actor,action,actionStep,frames,budget.remaining());
        var control=new Control(status,flow,continuation,decision,outcome,winners,nextDecision,mechanics==null?null:mechanics.snapshot());
        var changes=new TreeMap<String,RuleValue>();globals.forEach((key,value)->{if(!Objects.equals(before.globals().get(key),value))changes.put(key,value);});
        var changedEntities=new TreeMap<String,EvaluationContext.Entity>();entities.forEach((key,value)->{if(!Objects.equals(before.entities().get(key),value))changedEntities.put(key,value);});
        var removed=new HashSet<>(before.entities().keySet());removed.removeAll(entities.keySet());
        var commit=new Commit(runtime.version(),rules.fingerprint(),before.sessionId(),before.version(),Math.incrementExact(before.version()),changes,changedEntities,removed,control,events,trace);
        var state=RulesRuntime.replay(before,commit);
        SpatialValidation.validate(rules,state);
        try { RulesStateCodec.encode(state); }catch(IllegalArgumentException oversized) { throw new RuleEvaluationException("CHECKPOINT_LIMIT","",oversized.getMessage()); }
        return new Result(true,decision!=null?"WAITING_FOR_DECISION":status==Status.FINISHED?"GAME_FINISHED":"ACCEPTED","Transition accepted",state,commit);
    }
}
