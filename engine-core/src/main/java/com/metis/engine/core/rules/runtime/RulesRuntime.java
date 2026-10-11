package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;
import java.util.function.Consumer;

import static com.metis.engine.core.rules.runtime.RuntimeChecks.*;
import static com.metis.engine.core.rules.runtime.RulesState.*;

/** Authoritative headless interpreter; no legacy battle adapter or game-name branches. */
public final class RulesRuntime {
    public static final String VERSION="rules-runtime-4";
    public static final String MECHANICS_VERSION="rules-runtime-3";
    public static final String BASE_VERSION="rules-runtime-2";
    public static boolean supportsVersion(String version) { return VERSION.equals(version)||MECHANICS_VERSION.equals(version)||BASE_VERSION.equals(version); }
    private static final Map<String,OperationRegistry.Operation> OPERATIONS=OperationRegistry.standard().operations();
    private static final Set<String> CORE=OPERATIONS.keySet();
    private final CompiledRules definition;
    private final Map<String,EvaluationContext.QueryHandler> queries;
    private final Map<String,Block> blocks=new TreeMap<>();
    private record Block(List<CompiledRules.Instruction> instructions,List<Map<String,RuleType>> scopes,
                         Map<String,RuleType> loopParent,String loopBinding,RuleType loopElement,int loopMaximum) { }
    public record ManeuverControl(String entity,String speed,String yaw,String side,List<List<Integer>> chart) {
        public ManeuverControl { chart=chart.stream().map(List::copyOf).toList(); }
    }
    public record Candidate(String action,Map<String,RuleType> parameters,int step,String entityParameter,String entity,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ManeuverControl maneuver) {
        public Candidate { parameters=Map.copyOf(parameters); }
    }
    public record Pose(double x,double y,double heading) { }
    public record Movement(String entity,String board,String unit,Pose from,Pose to,List<SpatialGeometry.Point> path,double distance,
                           @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ManeuverGeometry.Trace maneuver) {
        public Movement { path=List.copyOf(path); }
        public Movement(String entity,String board,String unit,Pose from,Pose to,List<SpatialGeometry.Point> path,double distance) { this(entity,board,unit,from,to,path,distance,null); }
    }
    public record ResourceChange(String entity,String resource,long before,long after) { }
    public record Preview(boolean allowed,String code,String message,List<Movement> movements,List<ResourceChange> resources) {
        public Preview { movements=List.copyOf(movements);resources=List.copyOf(resources); }
        public Preview(boolean allowed,String code,String message) { this(allowed,code,message,List.of(),List.of()); }
    }
    public record OfferedAction(String action,Map<String,RuleValue> parameters,int priority) {
        public OfferedAction { parameters=Collections.unmodifiableMap(new TreeMap<>(parameters)); }
    }
    public RulesRuntime(CompiledRules definition) { this(definition,Map.of()); }
    public RulesRuntime(CompiledRules definition,Map<String,EvaluationContext.QueryHandler> queries) {
        this.definition=Objects.requireNonNull(definition);this.queries=Map.copyOf(queries);
        require(RulesCompiler.supportsVersion(definition.compilerVersion()),"COMPILER_INCOMPATIBLE","","Unsupported compiler contract");
        definition.procedures().forEach((name,procedure)->index("/procedures/"+name+"/steps",procedure.steps(),procedure.parameters(),null,null,null,0));
    }
    public CompiledRules definition() { return definition; }
    OperationRegistry.Operation operation(String name) { return OPERATIONS.get(name); }
    private boolean mechanical() { return definition.modules().keySet().stream().anyMatch(MechanicsRules.MODULES::contains); }
    public String version() { return definition.compilerVersion().equals(RulesCompiler.VERSION)?VERSION:mechanical()?MECHANICS_VERSION:BASE_VERSION; }
    Map<String,EvaluationContext.QueryHandler> queries() { return queries; }
    List<CompiledRules.Instruction> block(String path) {
        var block=blocks.get(path);require(block!=null,"CONTINUATION_INVALID",path,"Unknown instruction block");return block.instructions();
    }
    private void index(String path,List<CompiledRules.Instruction> instructions,Map<String,RuleType> entry,
                       Map<String,RuleType> loopParent,String loopBinding,RuleType loopElement,int loopMaximum) {
        var scope=new TreeMap<>(entry);var scopes=new ArrayList<Map<String,RuleType>>();scopes.add(Map.copyOf(scope));
        for(var instruction:instructions) {
            require(CORE.contains(instruction.operation()),"OPERATION_UNAVAILABLE",instruction.path(),"No execution handler for "+instruction.operation());
            instruction.blocks().forEach((name,body)->{
                var nested=new TreeMap<>(scope);boolean loop=instruction.operation().equals("core.forEach");
                RuleType element=loop?instruction.inputs().get("source").type().element():null;
                if(loop)nested.put(instruction.binding(),element);
                index(instruction.path()+"/"+name,body,nested,loop?Map.copyOf(scope):null,loop?instruction.binding():null,element,loop?Integer.parseInt(instruction.references().get("maxItems")):0);
            });
            if(instruction.operation().equals("core.let"))scope.put(instruction.binding(),instruction.inputs().get("value").type());
            if(instruction.operation().equals("core.choose"))scope.put(instruction.binding(),instruction.inputs().get("options").type().element());
            if(instruction.operation().equals("core.createEntity"))scope.put(instruction.binding(),RuleType.entity(instruction.references().get("type")));
            if(!instruction.operation().startsWith("core.")&&instruction.binding()!=null)scope.put(instruction.binding(),operation(instruction.operation()).result());
            scopes.add(Map.copyOf(scope));
        }
        blocks.put(path,new Block(List.copyOf(instructions),List.copyOf(scopes),loopParent,loopBinding,loopElement,loopMaximum));
    }
    public RulesState initial(String sessionId,List<String> players) {
        return initial(sessionId,players,mechanical()?RandomStream.secure():null);
    }
    /** Trusted deterministic setup hook for conformance tests, never exposed by the HTTP API. */
    public RulesState initial(String sessionId,List<String> players,RandomStream random) {
        require(sessionId!=null&&!sessionId.isBlank()&&sessionId.length()<=4000,"SESSION_INVALID","","Session identity required");
        require(players!=null&&!players.isEmpty()&&players.size()<=definition.limits().maxCollectionSize(),"PLAYERS_INVALID","","Provide a bounded nonempty player list");
        require(players.stream().allMatch(player->player!=null&&!player.isBlank()&&player.length()<=4096)&&new HashSet<>(players).size()==players.size(),"PLAYERS_INVALID","","Player identities must be nonempty and unique");
        var globals=new TreeMap<String,RuleValue>();definition.state().forEach((name,field)->globals.put(name,field.initial()));
        require(mechanical()==(random!=null),"RANDOM_CONFIGURATION","","Random state must match the runtime capability");
        return new RulesState(version(),definition.fingerprint(),sessionId,0,players,globals,Map.of(),new Control(Status.SETUP,Flow.setup(),null,null,null,List.of(),1,random==null?null:MechanicsState.initial(random)),List.of());
    }
    public Result start(RulesState state,long version) {
        return transition(state,version,false,machine->{
            require(state.control().status()==Status.SETUP&&state.control().continuation()==null,"START_NOT_ALLOWED","","Game has already started");
            machine.system("started","/setup");machine.begin(Purpose.SETUP,definition.setup(),Map.of(),null,null,-1);machine.run();
        });
    }
    public Result act(RulesState state,String actor,String action,Map<String,RuleValue> parameters,long version) {
        return transition(state,version,false,machine->executeAction(machine,actor,action,parameters));
    }
    private void executeAction(RuntimeMachine machine,String actor,String action,Map<String,RuleValue> parameters) {
        ready(machine.before);int step=validateAction(machine,actor,action,parameters);
        var declared=definition.actions().get(action);var locals=actionLocals(machine,actor,declared,parameters);
        var args=machine.arguments(declared.arguments(),definition.procedures().get(declared.procedure()).parameters(),locals,"/actions/"+action+"/args");
        machine.begin(Purpose.ACTION,declared.procedure(),args,actor,action,step);machine.system("actionStarted","/actions/"+action);machine.run();
    }
    public Result respond(RulesState state,String actor,String decision,int option,long version) {
        return transition(state,version,true,machine->{
            require(state.control().continuation()!=null&&state.control().decision()!=null,"NO_DECISION","","No decision is pending");machine.respond(actor,decision,option);
        });
    }
    public Result activate(RulesState state,String actor,String entity,long version) { return control(state,version,machine->machine.activate(actor,entity)); }
    public Result pass(RulesState state,String actor,long version) { return control(state,version,machine->machine.pass(actor)); }
    public Result advanceStep(RulesState state,String actor,long version) { return control(state,version,machine->machine.advanceStep(actor)); }
    private Result control(RulesState state,long version,Consumer<RuntimeMachine> action) { return transition(state,version,false,machine->{ready(state);action.accept(machine);}); }
    private Result transition(RulesState state,long version,boolean resume,Consumer<RuntimeMachine> action) {
        try {
            compatible(state);require(state.version()==version,"STALE_STATE","","Expected version does not match checkpoint");
            var machine=new RuntimeMachine(this,state,resume);action.accept(machine);return machine.commit();
        } catch(RuleEvaluationException error) { return new Result(false,error.code(),error.getMessage(),null,null); }
        catch(ArithmeticException error) { return new Result(false,"NUMERIC_ERROR",error.getMessage(),null,null); }
        catch(IllegalArgumentException error) { return new Result(false,"VALUE_TYPE",error.getMessage(),null,null); }
    }
    private void compatible(RulesState state) {
        require(state!=null,"STATE_REQUIRED","","Checkpoint is required");
        require(version().equals(state.runtimeVersion())&&definition.fingerprint().equals(state.definitionFingerprint()),"SNAPSHOT_INCOMPATIBLE","","Runtime/definition fingerprint differs from checkpoint");
    }
    private static void ready(RulesState state) {
        require(state.control().decision()==null&&state.control().continuation()==null,"DECISION_PENDING","","Complete the pending procedure first");
        require(state.control().status()==Status.RUNNING,"GAME_NOT_RUNNING","","Game is not running");
    }
    private Map<String,RuleValue> actionLocals(RuntimeMachine machine,String actor,CompiledRules.Action action,Map<String,RuleValue> parameters) {
        require(parameters!=null&&parameters.keySet().equals(action.parameters().keySet()),"ARGUMENTS_INVALID","","Supply exactly the declared action inputs");
        var locals=new TreeMap<String,RuleValue>();
        action.parameters().forEach((name,type)->locals.put(name,machine.checked(parameters.get(name),type,"/parameters/"+name,true)));
        locals.put("actor",RuleValue.player(actor));return locals;
    }
    private int validateAction(RuntimeMachine machine,String actor,String name,Map<String,RuleValue> parameters) {
        machine.actor=actor;
        machine.actorAllowed(actor);require(name!=null&&definition.actions().containsKey(name),"ACTION_UNKNOWN","","Unknown action");var action=definition.actions().get(name);
        require(!machine.entityPhase()||machine.flow.entity()!=null,"ENTITY_REQUIRED","","Select an entity before acting");
        int step=machine.actionStep(actor,name);var locals=actionLocals(machine,actor,action,parameters);
        if(machine.entityPhase()) {
            var subject=(RuleValue.EntityRef)locals.get(machine.phase().entityParameter()).value();
            require(subject.id().equals(machine.flow.entity())&&machine.eligible(actor).contains(subject.id()),"ENTITY_INELIGIBLE","","Action subject must be the active eligible entity");
        }
        require(machine.evaluate(action.availableWhen(),locals).bool(),"ACTION_INELIGIBLE","/actions/"+name,"Action eligibility condition is false");return step;
    }
    public Preview preview(RulesState state,String actor,String action,Map<String,RuleValue> parameters) {
        try {
            compatible(state);ready(state);var machine=visibleMachine(state,actor);
            validateAction(machine,actor,action,parameters);
            if(!definition.actions().get(action).movementPreview())return new Preview(true,"ALLOWED","Action inputs and budget are eligible");
            // Start with a fresh budget: preview executes exactly the command path once.
            machine=visibleMachine(state,actor);executeAction(machine,actor,action,parameters);machine.commit();
            require(!machine.movements.isEmpty(),"PREVIEW_NO_MOVEMENT","","This branch did not execute movement");
            var movements=machine.movements.stream().map(m->new Movement(m.entity(),m.board(),m.unit(),new Pose(m.from().x(),m.from().y(),m.from().heading()),new Pose(m.to().x(),m.to().y(),m.to().heading()),m.path(),m.distance(),m.maneuver())).toList();
            var resources=new ArrayList<ResourceChange>();
            for(var before:machine.before.entities().values()) {
                var after=machine.entities.get(before.id());if(after==null)continue;
                for(var resource:before.resources().entrySet())if(after.resources().containsKey(resource.getKey())&&!after.resources().get(resource.getKey()).equals(resource.getValue()))
                    resources.add(new ResourceChange(before.id(),resource.getKey(),resource.getValue(),after.resources().get(resource.getKey())));
            }
            return new Preview(true,"ALLOWED","Deterministic movement action validated",movements,resources);
        }
        catch(RuleEvaluationException error) { return new Preview(false,error.code(),error.getMessage()); }
        catch(ArithmeticException error) { return new Preview(false,"NUMERIC_ERROR",error.getMessage()); }
        catch(IllegalArgumentException error) { return new Preview(false,"VALUE_TYPE",error.getMessage()); }
    }
    private RuntimeMachine visibleMachine(RulesState state,String actor) {
        require(actor!=null&&state.players().contains(actor),"PLAYER_UNKNOWN","","Unknown player");
        // Older captures retain their original eligibility contract.
        if(!version().equals(VERSION)) { var machine=new RuntimeMachine(this,state,false);machine.actor=actor;return machine; }
        var view=RulesView.project(definition,state,Set.of(actor));var control=state.control();
        var projected=new RulesState(state.runtimeVersion(),state.definitionFingerprint(),state.sessionId(),state.version(),state.players(),view.globals(),view.entities(),
                new Control(control.status(),control.flow(),null,null,control.outcome(),control.winners(),control.nextDecision(),RulesView.visibleMechanics(state,actor)),view.events());
        var machine=new RuntimeMachine(this,projected,false,true);machine.actor=actor;return machine;
    }
    /** Bounded finite suggestions from declared domains; eligibility never examines an opponent's secrets. */
    public List<OfferedAction> offeredActions(RulesState state,String actor) {
        compatible(state);var offered=new ArrayList<OfferedAction>();int maximum=Math.min(256,definition.limits().maxCollectionSize()),attempts=0;
        for(var candidate:candidates(state,actor).stream().sorted(Comparator.<Candidate>comparingInt(c->definition.actions().get(c.action()).priority()).reversed().thenComparing(Candidate::action)).toList()) {
            var declared=definition.actions().get(candidate.action());if(!declared.choices().keySet().containsAll(declared.parameters().keySet()))continue;
            try {
                var machine=visibleMachine(state,actor);var combinations=new ArrayList<Map<String,RuleValue>>();combinations.add(Map.of());
                for(String parameter:new TreeSet<>(declared.parameters().keySet())) {
                    var expanded=new ArrayList<Map<String,RuleValue>>();
                    for(var partial:combinations) {
                        var locals=new TreeMap<>(partial);locals.put("actor",RuleValue.player(actor));
                        var domain=machine.evaluate(declared.choices().get(parameter),locals).items();
                        for(var value:domain) {
                        if(expanded.size()>=maximum)break;
                        if(parameter.equals(candidate.entityParameter())&&candidate.entity()!=null&&!((RuleValue.EntityRef)value.value()).id().equals(candidate.entity()))continue;
                        var next=new TreeMap<>(partial);next.put(parameter,value);expanded.add(next);
                        }
                    }
                    combinations=expanded;
                }
                for(var parameters:combinations) {
                    if(attempts++>=256)return List.copyOf(offered);
                    if(preview(state,actor,candidate.action(),parameters).allowed()) {
                        offered.add(new OfferedAction(candidate.action(),parameters,declared.priority()));if(offered.size()>=maximum)return List.copyOf(offered);
                    }
                }
            }catch(RuleEvaluationException unavailable) { /* A domain requiring hidden or unavailable data offers nothing. */ }
        }
        return List.copyOf(offered);
    }
    /** Candidate forms, not invented finite domains for arbitrary string/numeric inputs. */
    public List<Candidate> candidates(RulesState state,String actor) {
        try {
            compatible(state);ready(state);var machine=visibleMachine(state,actor);machine.actorAllowed(actor);
            if(machine.entityPhase()&&machine.flow.entity()==null)return List.of();
            var result=new ArrayList<Candidate>();
            for(var entry:definition.actions().entrySet()) {
                int step;
                try { step=machine.actionStep(actor,entry.getKey()); }catch(RuleEvaluationException unavailable){continue;}
                if(entry.getValue().parameters().isEmpty()&&!preview(state,actor,entry.getKey(),Map.of()).allowed())continue;
                result.add(new Candidate(entry.getKey(),entry.getValue().parameters(),step,machine.phase().entityParameter(),machine.flow.entity(),maneuverControl(machine,entry.getValue())));
            }
            return List.copyOf(result);
        } catch(RuleEvaluationException unavailable) { return List.of(); }
    }
    private ManeuverControl maneuverControl(RuntimeMachine machine,CompiledRules.Action action) {
        var hint=action.maneuver();if(hint==null)return null;
        var entity=machine.entities.get(machine.flow.entity());if(entity==null)return null;
        var rows=machine.evaluate(hint.chart(),Map.of("actor",RuleValue.player(machine.actor),hint.entity(),RuleValue.entity(entity.type(),entity.id()))).items();
        require(!rows.isEmpty()&&rows.size()<=9,"MANEUVER_CHART","","Invalid control chart");var chart=new ArrayList<List<Integer>>();
        for(int speed=0;speed<rows.size();speed++) {
            var row=rows.get(speed).items();require(row.size()==speed&&row.stream().allMatch(v->v.integer()>=0&&v.integer()<=8),"MANEUVER_CHART","","Invalid control chart");
            chart.add(row.stream().map(v->(int)v.integer()).toList());
        }
        return new ManeuverControl(hint.entity(),hint.speed(),hint.yaw(),hint.side(),chart);
    }
    public List<String> eligibleEntities(RulesState state,String actor) {
        compatible(state);ready(state);var machine=visibleMachine(state,actor);machine.actorAllowed(actor);
        require(machine.entityPhase(),"ENTITY_PHASE_REQUIRED","","Phase has no entities");return List.copyOf(machine.eligible(actor));
    }
    public RulesCpu.Input decisionInput(RulesState state,String actor) {
        var controls=controls(state,actor);
        return new RulesCpu.Input(actor,RulesView.project(definition,state,Set.of(actor)),offeredActions(state,actor),controls,controls.contains("activate")?eligibleEntities(state,actor):List.of());
    }
    public List<String> controls(RulesState state,String actor) {
        try {
            compatible(state);
            if(state.control().decision()!=null)return state.control().decision().player().equals(actor)?List.of("respond"):List.of();
            ready(state);var machine=visibleMachine(state,actor);machine.actorAllowed(actor);var result=new ArrayList<String>();
            if(machine.entityPhase()&&machine.flow.entity()==null)return machine.eligible(actor).isEmpty()?List.of():List.of("activate");
            if(machine.minimumsMet(actor))result.add("pass");
            var progress=machine.progress(actor);var steps=machine.phase().steps();
            if(progress.step()+1<steps.size()&&progress.uses().getOrDefault(steps.get(progress.step()).id(),0)>=steps.get(progress.step()).minimum())result.add("advanceStep");
            return List.copyOf(result);
        } catch(RuleEvaluationException unavailable) { return List.of(); }
    }
    public RulesState restore(String json) {
        var state=RulesStateCodec.decode(json);validateCheckpoint(state);return state;
    }
    /** For trusted storage/replay only; clients submit commands, never checkpoints. */
    public void validateCheckpoint(RulesState state) {
        compatible(state);initial(state.sessionId(),state.players(),state.control().mechanics()==null?null:state.control().mechanics().random());var validationBudget=new EvaluationContext.Budget(1_000_000);
        require(state.globals().keySet().equals(definition.state().keySet()),"SNAPSHOT_INVALID","/globals","State fields differ from definition");
        state.globals().forEach((name,value)->checkpointValue(state,value,definition.state().get(name).type(),"/globals/"+name,validationBudget));
        require(state.entities().size()<=definition.limits().maxCollectionSize(),"COLLECTION_LIMIT","/entities","Too many entities");
        state.entities().forEach((id,entity)->{
            var schema=definition.entities().get(entity.type());require(schema!=null&&id.equals(entity.id())&&!id.isBlank(),"SNAPSHOT_INVALID","/entities","Invalid entity identity/type");
            require(schema.ownership()==CompiledRules.Ownership.PLAYER?entity.owner()!=null&&state.players().contains(entity.owner()):entity.owner()==null,"SNAPSHOT_INVALID","/entities/"+id,"Invalid owner");
            require(entity.attributes().keySet().equals(schema.attributes().keySet())&&entity.resources().keySet().equals(schema.resources().keySet()),"SNAPSHOT_INVALID","/entities/"+id,"Invalid entity fields");
            entity.attributes().forEach((name,value)->checkpointValue(state,value,schema.attributes().get(name).type(),"/entities/"+id+"/"+name,validationBudget));
            entity.resources().forEach((name,value)->resource(schema.resources().get(name),value,"/entities/"+id+"/"+name));
        });
        var control=state.control();var flow=control.flow();var phase=flow.phase()==null?null:definition.flow().phases().get(flow.phase());
        require(flow.round()>=0&&flow.playerIndex()>=-1&&flow.playerIndex()<state.players().size()&&flow.groupCompleted()>=0&&control.nextDecision()>0,"SNAPSHOT_INVALID","/control","Invalid counters");
        require(control.status()==Status.SETUP?flow.phase()==null:phase!=null||control.status()==Status.FINISHED,"SNAPSHOT_INVALID","/control/flow","Unknown phase");
        require(state.players().containsAll(flow.completedPlayers()),"SNAPSHOT_INVALID","/control/flow","Unknown completed player");
        flow.progress().forEach((player,progress)->{
            require(phase!=null&&state.players().contains(player)&&progress.step()>=0&&progress.step()<=phase.steps().size(),"SNAPSHOT_INVALID","/control/progress","Invalid progress");
            progress.uses().forEach((name,count)->require(count>=0&&phase.steps().stream().anyMatch(step->step.id().equals(name)&&count<=step.maximum()),"SNAPSHOT_INVALID","/control/progress","Invalid budget use"));
        });
        require(control.winners().stream().allMatch(state.players()::contains)&&new HashSet<>(control.winners()).size()==control.winners().size(),"SNAPSHOT_INVALID","/control/winners","Invalid winners");
        require(control.status()!=Status.FINISHED||control.outcome()!=null,"SNAPSHOT_INVALID","/control/outcome","Terminal outcome missing");
        var continuation=control.continuation();var decision=control.decision();
        require((continuation==null)==(decision==null),"CONTINUATION_INVALID","/control","Decision and continuation must occur together");
        if(continuation!=null) {
            require(control.status()!=Status.FINISHED&&continuation.remainingBudget()>=0&&continuation.remainingBudget()<=definition.limits().maxInstructions(),"CONTINUATION_INVALID","/control","Invalid continuation budget/status");
            require(!continuation.frames().isEmpty()&&continuation.frames().size()<=(definition.limits().maxCallDepth()+1)*64,"CONTINUATION_INVALID","/control","Invalid stack size");
            require(continuation.frames().getLast().kind()!=FrameKind.LOOP,"CONTINUATION_INVALID","/control","Decision requires an active instruction or window");
            require(continuation.frames().stream().filter(f->f.kind()==FrameKind.WINDOW).count()<=definition.limits().maxWindowDepth(),"WINDOW_DEPTH","/control","Reaction nesting exceeded");
            require(state.players().contains(decision.player())&&!decision.options().isEmpty()&&decision.visibility()!=CompiledRules.Audience.SERVER,"CONTINUATION_INVALID",decision.path(),"Invalid decision");
            require(decision.id().equals(state.sessionId()+":"+(control.nextDecision()-1)),"CONTINUATION_INVALID",decision.path(),"Decision identity differs from checkpoint counter");
            if(continuation.purpose()==Purpose.ACTION)require(continuation.actor()!=null&&state.players().contains(continuation.actor())&&continuation.action()!=null&&definition.actions().containsKey(continuation.action())&&phase!=null&&continuation.actionStep()>=0&&continuation.actionStep()<phase.steps().size(),"CONTINUATION_INVALID","/control","Invalid action continuation");
            for(int i=0;i<continuation.frames().size();i++) {
                var frame=continuation.frames().get(i);var block=blocks.get(frame.block());
                if(frame.kind()==FrameKind.WINDOW) {
                    var window=definition.windows().get(frame.block().replaceFirst("^/windows/",""));
                    require(frame.block().startsWith("/windows/")&&window!=null&&frame.index()>=0&&frame.index()<=frame.items().size()&&frame.items().size()<=window.maxResponders(),"CONTINUATION_INVALID",frame.block(),"Invalid reaction frame");
                    require(frame.callDepth()>0&&frame.callDepth()<=definition.limits().maxCallDepth()&&frame.binding()==null&&frame.locals().keySet().equals(window.parameters().keySet()),"CONTINUATION_INVALID",frame.block(),"Invalid reaction scope");
                    frame.locals().forEach((name,value)->checkpointValue(state,value,window.parameters().get(name),frame.block(),validationBudget));
                    frame.items().forEach(value->{checkpointValue(state,value,RuleType.PLAYER,frame.block(),validationBudget);require(state.players().contains(value.value()),"CONTINUATION_INVALID",frame.block(),"Unknown responder");});
                    require(new HashSet<>(frame.items()).size()==frame.items().size(),"CONTINUATION_INVALID",frame.block(),"Duplicate responder");
                    if(i==continuation.frames().size()-1) {
                        require(frame.index()<frame.items().size()&&frame.items().get(frame.index()).value().equals(decision.player())&&"@window".equals(decision.binding())&&frame.block().equals(decision.path())&&decision.visibility()==window.visibility(),"CONTINUATION_INVALID",frame.block(),"Decision does not match reaction frame");
                        for(var option:decision.options())require(option.type().equals(RuleType.STRING)&&((option.value().equals("$pass")&&window.allowPass())||window.choices().stream().anyMatch(c->c.id().equals(option.value()))),"CONTINUATION_INVALID",frame.block(),"Unknown reaction option");
                    }
                    continue;
                }
                require(block!=null&&frame.callDepth()>0&&frame.callDepth()<=definition.limits().maxCallDepth()&&frame.index()>=0,"CONTINUATION_INVALID",frame.block(),"Invalid stack frame");
                Map<String,RuleType> expected;
                if(frame.kind()==FrameKind.LOOP) {
                    require(block.loopBinding()!=null&&block.loopBinding().equals(frame.binding())&&frame.index()<=frame.items().size()&&frame.items().size()<=block.loopMaximum(),"CONTINUATION_INVALID",frame.block(),"Invalid loop continuation");
                    frame.items().forEach(value->checkpointValue(state,value,block.loopElement(),frame.block(),validationBudget));expected=block.loopParent();
                } else {
                    require(frame.index()<=block.instructions().size()&&frame.items().isEmpty()&&frame.binding()==null,"CONTINUATION_INVALID",frame.block(),"Invalid block position");
                    expected=new TreeMap<>(block.scopes().get(frame.index()));
                    if(i==continuation.frames().size()-1) {
                        require(frame.index()>0,"CONTINUATION_INVALID",frame.block(),"Decision has no originating instruction");
                        var instruction=block.instructions().get(frame.index()-1);
                        require(instruction.operation().equals("core.choose")&&instruction.binding().equals(decision.binding())&&instruction.path().equals(decision.path())&&decision.visibility().name().equals(instruction.references().get("visibility")),"CONTINUATION_INVALID",frame.block(),"Decision does not match continuation");
                        decision.options().forEach(value->checkpointValue(state,value,instruction.inputs().get("options").type().element(),decision.path(),validationBudget));expected.remove(decision.binding());
                    }
                }
                require(frame.locals().keySet().equals(expected.keySet()),"CONTINUATION_INVALID",frame.block(),"Invalid local scope");
                var types=expected;frame.locals().forEach((name,value)->checkpointValue(state,value,types.get(name),frame.block(),validationBudget));
            }
        }
        require(state.events().size()<=100000,"EVENT_LIMIT","/events","Too many events");
        for(int i=0;i<state.events().size();i++)require(state.events().get(i).sequence()==i+1L,"SNAPSHOT_INVALID","/events","Invalid event sequence");
        if(control.mechanics()!=null)MechanicsValidation.validate(definition,state);
        SpatialValidation.validate(definition,state);
    }
    private void checkpointValue(RulesState state,RuleValue value,RuleType type,String path,EvaluationContext.Budget budget) {
        RuntimeChecks.value(value,type,definition,state.players(),state.entities(),budget,path,false);
    }
    /** Reconstruct recorded checkpoints without evaluating a rule or invoking a query. */
    public static RulesState replay(RulesState before,Commit commit) {
        require(before.runtimeVersion().equals(commit.runtimeVersion())&&supportsVersion(commit.runtimeVersion())&&before.definitionFingerprint().equals(commit.definitionFingerprint())&&before.sessionId().equals(commit.sessionId()),"SNAPSHOT_INCOMPATIBLE","","Commit belongs to a different runtime/session/definition");
        require(before.version()==commit.beforeVersion()&&commit.version()==Math.incrementExact(before.version()),"STALE_COMMIT","","Commit sequence mismatch");
        var globals=new TreeMap<>(before.globals());globals.putAll(commit.globals());var entities=new TreeMap<>(before.entities());
        commit.removedEntities().forEach(entities::remove);entities.putAll(commit.entities());var events=new ArrayList<>(before.events());
        for(var event:commit.events()) { require(event.sequence()==events.size()+1L,"EVENT_SEQUENCE","","Commit event sequence mismatch");events.add(event); }
        return new RulesState(before.runtimeVersion(),before.definitionFingerprint(),before.sessionId(),commit.version(),before.players(),globals,entities,commit.control(),events);
    }
}
