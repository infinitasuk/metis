package com.metis.engine.api;

import com.fasterxml.jackson.databind.*;
import com.metis.engine.core.rules.*;
import com.metis.engine.core.rules.runtime.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class RulesService {
    private static final ObjectMapper INPUT=com.fasterxml.jackson.databind.json.JsonMapper.builder(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(1_000_000).maxNumberLength(256).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final RulesRepository repository;
    private final ObjectMapper json;
    private final RulesCompiler compiler=new RulesCompiler();
    public RulesService(RulesRepository repository,ObjectMapper json) { this.repository=repository;this.json=json; }
    public static final class Failure extends RuntimeException {
        final int status;final String code;final List<RuleDiagnostic> diagnostics;
        Failure(int status,String code) { this(status,code,List.of()); }
        Failure(int status,String code,List<RuleDiagnostic> diagnostics) { super(code);this.status=status;this.code=code;this.diagnostics=List.copyOf(diagnostics); }
        public int status() { return status; } public String code() { return code; } public List<RuleDiagnostic> diagnostics() { return diagnostics; }
    }
    public record DefinitionView(long id,String key,String revision,long version,String lifecycle,String fingerprint,String compilerVersion,String runtimeVersion,String source) { }
    public record SeatView(String playerId,String kind) { }
    public record CpuStatus(int limit,long steps,String pauseReason) { }
    public record SessionView(UUID id,long definitionId,String fingerprint,boolean paused,Set<String> controlledPlayers,boolean canManage,RulesView state,List<SeatView> seats,CpuStatus cpu) { }
    public record CommandResponse(boolean accepted,String code,SessionView view) { }
    public record StoredResponse(int status,String json) { }
    public record ActorActions(String actor,List<RulesRuntime.Candidate> candidates,List<String> controls,List<String> entities,List<RulesRuntime.OfferedAction> offered) { }
    public record Choices(long version,boolean paused,List<String> management,List<ActorActions> actors) { }
    public record Workspace(SessionView view,Choices choices,Map<String,Object> presentation) { }
    public record WorkspaceReply(String etag,Workspace body) { }
    private static void check(boolean valid,int status,String code) { if(!valid)throw new Failure(status,code); }
    private static void admin(MetisUser user) { check(user.admin(),403,"ADMIN_REQUIRED"); }
    private RulesRepository.Definition definition(long id,boolean lock) { return repository.definition(id,lock).orElseThrow(()->new Failure(404,"DEFINITION_NOT_FOUND")); }
    private DefinitionView view(RulesRepository.Definition d,boolean source) { return new DefinitionView(d.id(),d.key(),d.revision(),d.version(),d.lifecycle(),d.fingerprint(),d.compiler(),d.runtime(),source?d.source():null); }
    public List<DefinitionView> definitions(MetisUser user,boolean drafts) { if(drafts)admin(user);return repository.definitions().stream().filter(d->drafts||d.lifecycle().equals("published")).map(d->view(d,false)).toList(); }
    public DefinitionView definition(long id,MetisUser user) { admin(user);return view(definition(id,false),true); }
    private CatalogueRosters publishedRoster(long id) {
        var source=definition(id,false);check(source.lifecycle().equals("published"),404,"DEFINITION_NOT_FOUND");
        return new CatalogueRosters(source.executable());
    }
    public CatalogueRosters.View definitionRoster(long id) { return publishedRoster(id).view(); }
    public CatalogueRosters.Validation validateDefinitionRoster(long id,JsonNode request) { return publishedRoster(id).validateRequest(request); }
    public RulesResolver.Result validate(long id,MetisUser user) { admin(user);return resolve(definition(id,false).source()); }
    private RulesResolver.Result resolve(String source) { return new RulesResolver(compiler).resolve(source,ref->repository.published(ref.id(),ref.revision()).map(RulesRepository.Definition::source).orElse(null)); }
    public Map<String,Object> resolved(long id,MetisUser user) {
        admin(user);var definition=definition(id,false);
        if(definition.resolved()!=null)return Map.of("valid",true,"source",definition.resolved(),"resolution",resolution(definition.resolution()),"fingerprint",definition.fingerprint());
        var result=resolve(definition.source());if(!result.valid())return Map.of("valid",false,"diagnostics",result.diagnostics());
        return Map.of("valid",true,"source",result.definition().canonicalJson(),"resolution",result.resolution(),"fingerprint",result.definition().fingerprint());
    }
    private String source(JsonNode source) {
        if(source!=null&&source.isTextual())source=parse(source.textValue());
        check(source!=null&&source.isObject()&&source.path("id").isTextual()&&source.path("revision").isTextual(),400,"DEFINITION_ID_REQUIRED");
        check(source.get("id").asText().length()<=64&&source.get("revision").asText().length()<=64&&!source.get("id").asText().isBlank()&&!source.get("revision").asText().isBlank(),400,"DEFINITION_ID_INVALID");
        String value=canonical(source);check(value.length()<=1_000_000,400,"DEFINITION_TOO_LARGE");return value;
    }
    @Transactional public DefinitionView createDefinition(JsonNode source,MetisUser user) { admin(user);return view(definition(repository.createDefinition(source(source),user.userId()),false),true); }
    @Transactional public DefinitionView updateDefinition(long id,long version,JsonNode source,MetisUser user) {
        admin(user);var current=definition(id,true);check(current.lifecycle().equals("draft"),409,"DEFINITION_IMMUTABLE");check(current.version()==version,409,"STALE_DEFINITION");
        repository.updateDefinition(id,version,source(source));return view(definition(id,false),true);
    }
    @Transactional public DefinitionView publish(long id,long version,MetisUser user) {
        admin(user);var current=definition(id,true);check(current.lifecycle().equals("draft"),409,"DEFINITION_IMMUTABLE");check(current.version()==version,409,"STALE_DEFINITION");
        var result=resolve(current.source());if(!result.valid())throw new Failure(422,"DEFINITION_INVALID",result.diagnostics());
        var rules=result.definition();var runtime=new RulesRuntime(rules); // Installed execution capability, not just syntax.
        repository.publish(id,version,current.source(),rules.canonicalJson(),rules.fingerprint(),rules.compilerVersion(),runtime.version(),write(rules.modules()),write(result.resolution()));return view(definition(id,false),true);
    }
    private RulesRepository.Session session(UUID id,MetisUser user,boolean lock) {
        var row=repository.session(id,lock).orElseThrow(()->new Failure(404,"SESSION_NOT_FOUND"));check(repository.canView(id,user),404,"SESSION_NOT_FOUND");return row;
    }
    private Set<String> owned(UUID id,MetisUser user) { var result=new TreeSet<String>();repository.seats(id).stream().filter(s->s.kind().equals("HUMAN")&&s.userId()==user.userId()).forEach(s->result.add(s.playerId()));return Collections.unmodifiableSet(result); }
    private RulesRuntime runtime(RulesRepository.Session session) {
        check(RulesCompiler.supportsVersion(session.compiler())&&RulesRuntime.supportsVersion(session.runtime()),409,"SNAPSHOT_INCOMPATIBLE");
        check(session.resolution()==null||RulesResolver.VERSION.equals(resolution(session.resolution()).path("resolverVersion").asText()),409,"SNAPSHOT_INCOMPATIBLE");
        var compiled=compiler.compile(session.source());check(compiled.valid()&&compiled.definition().compilerVersion().equals(session.compiler())&&compiled.definition().fingerprint().equals(session.fingerprint()),409,"SNAPSHOT_INCOMPATIBLE");
        var runtime=new RulesRuntime(compiled.definition());check(runtime.version().equals(session.runtime()),409,"SNAPSHOT_INCOMPATIBLE");return runtime;
    }
    private SessionView view(RulesRepository.Session row,RulesRuntime runtime,RulesState state,boolean paused,MetisUser user) {
        var owned=owned(row.id(),user);long steps=0;String reason=null;
        for(var event:state.events()) {
            if(event.type().equals("rules.resumed")) { steps=0;reason=null; }
            if(event.type().equals("rules.cpuCommand"))steps++;
            if(event.type().equals("rules.paused"))reason=event.values().containsKey("reason")?(String)event.values().get("reason").value():"MANUAL_PAUSE";
        }
        return new SessionView(row.id(),row.definitionId(),row.fingerprint(),paused,owned,row.owner()==user.userId(),RulesView.project(runtime.definition(),state,owned),repository.seats(row.id()).stream().map(s->new SeatView(s.playerId(),s.kind())).toList(),new CpuStatus(row.cpuLimit(),steps,paused?(reason==null?row.cpuError():reason):null));
    }
    @Transactional public SessionView createSession(JsonNode request,MetisUser user) {
        check(request!=null&&request.isObject(),400,"REQUEST_FIELDS_INVALID");
        var fields=new HashSet<>(Set.of("definitionId","visibility","seats","viewers"));
        if(request.has("cpuCommandLimit"))fields.add("cpuCommandLimit");if(request.has("rosters"))fields.add("rosters");
        shape(request,fields);long definitionId=integer(request,"definitionId");
        long cpuLimit=request.has("cpuCommandLimit")?integer(request,"cpuCommandLimit"):1000;check(cpuLimit>=1&&cpuLimit<=10000,400,"CPU_LIMIT_INVALID");
        String visibility=text(request,"visibility");check(Set.of("public","private").contains(visibility),400,"VISIBILITY_INVALID");
        var definition=definition(definitionId,false);check(definition.lifecycle().equals("published"),409,"DEFINITION_NOT_PUBLISHED");
        var compiled=compiler.compile(definition.executable());check(compiled.valid()&&compiled.definition().fingerprint().equals(definition.fingerprint())&&compiled.definition().compilerVersion().equals(definition.compiler()),409,"SNAPSHOT_INCOMPATIBLE");
        var runtime=new RulesRuntime(compiled.definition());check(runtime.version().equals(definition.runtime()),409,"SNAPSHOT_INCOMPATIBLE");var players=new ArrayList<String>();
        check(request.get("seats").isArray()&&request.get("viewers").isArray(),400,"MEMBERSHIP_INVALID");
        var seats=json.createArrayNode();
        for(var seat:request.get("seats")) {
            String kind=seat.has("kind")?text(seat,"kind"):"HUMAN";check(Set.of("HUMAN","CPU").contains(kind),400,"SEAT_KIND_INVALID");
            shape(seat,kind.equals("CPU")?Set.of("playerId","kind"):seat.has("kind")?Set.of("playerId","userId","kind"):Set.of("playerId","userId"));
            String player=text(seat,"playerId");check(player.matches("[a-zA-Z0-9_-]{1,128}"),400,"PLAYER_ID_INVALID");players.add(player);
            seats.addObject().put("playerId",player).put("kind",kind).put("userId",kind.equals("CPU")?user.userId():integer(seat,"userId"));
        }
        check(seats.findValuesAsText("kind").stream().noneMatch("CPU"::equals)||runtime.version().equals(RulesRuntime.VERSION),422,"CPU_REQUIRES_PROJECTED_RUNTIME");
        var viewers=new HashSet<Long>();for(var viewer:request.get("viewers"))check(viewer.isIntegralNumber()&&viewer.canConvertToLong()&&viewers.add(viewer.longValue()),400,"MEMBERSHIP_INVALID");
        UUID id=UUID.randomUUID();var initial=runtime.initial(id.toString(),players);
        initial=new CatalogueRosters(compiled.definition()).select(initial,request.get("rosters"));
        runtime.validateCheckpoint(initial);
        repository.createSession(id,definitionId,user.userId(),visibility,RulesStateCodec.encode(initial),seats.toString(),request.get("viewers").toString(),(int)cpuLimit);
        return view(session(id,user,false),runtime,initial,false,user);
    }
    public SessionView state(UUID id,MetisUser user) { var row=session(id,user,false);var runtime=runtime(row);return view(row,runtime,runtime.restore(row.checkpoint()),row.paused(),user); }
    public CatalogueRosters.Capture roster(UUID id,MetisUser user) {
        var row=session(id,user,false);var runtime=runtime(row);
        return new CatalogueRosters(runtime.definition()).captured(runtime.restore(row.initial()));
    }
    public Map<String,Object> compatibility(UUID id,MetisUser user) {
        var row=session(id,user,false);boolean valid;
        try { var runtime=runtime(row);runtime.restore(row.checkpoint());valid=true; }catch(RuntimeException error){valid=false;}
        return Map.of("compatible",valid,"fingerprint",row.fingerprint(),"compilerVersion",row.compiler(),"runtimeVersion",row.runtime(),"resolverVersion",row.resolution()==null?"none":resolution(row.resolution()).path("resolverVersion").asText());
    }
    public SessionView history(UUID id,long version,MetisUser user) {
        var row=session(id,user,false);check(version>=0&&version<=row.version(),404,"HISTORY_NOT_FOUND");var runtime=runtime(row);var state=runtime.restore(row.initial());boolean paused=false;
        for(var transition:repository.transitions(id,version)) { state=RulesRuntime.replay(state,RulesStateCodec.decodeCommit(transition.commit()));paused=transition.paused(); }
        check(state.version()==version,409,"HISTORY_INCOMPLETE");return view(row,runtime,state,paused,user);
    }
    public Choices actions(UUID id,MetisUser user) {
        var row=session(id,user,false);var runtime=runtime(row);var state=runtime.restore(row.checkpoint());
        return choices(row,runtime,state,user);
    }
    public List<RulesRepository.SessionSummary> sessions(MetisUser user) { return repository.sessions(user); }
    public WorkspaceReply workspace(UUID id,MetisUser user,String previous) {
        if(previous!=null) { String stamp=repository.workspaceStamp(id,user);check(stamp!=null,404,"SESSION_NOT_FOUND");String etag="\""+digest(stamp)+"\"";if(previous.equals(etag))return new WorkspaceReply(etag,null); }
        var row=session(id,user,false);var runtime=runtime(row);var state=runtime.restore(row.checkpoint());var space=runtime.definition().space();
        String etag="\""+digest(id+":"+row.fingerprint()+":"+row.runtime()+":"+row.version()+":"+row.paused()+":"+user.userId())+"\"";
        return new WorkspaceReply(etag,new Workspace(view(row,runtime,state,row.paused(),user),choices(row,runtime,state,user),Map.of("name",runtime.definition().id(),"revision",runtime.definition().revision(),"boards",space.boards(),"bodies",space.bodies(),"zones",space.zones())));
    }
    private Choices choices(RulesRepository.Session row,RulesRuntime runtime,RulesState state,MetisUser user) {
        UUID id=row.id();var actors=new ArrayList<ActorActions>();var management=new ArrayList<String>();
        if(row.owner()==user.userId()&&state.control().status()!=RulesState.Status.FINISHED) {
            if(row.paused())management.add("resume");else { management.add("pause");if(state.control().status()==RulesState.Status.SETUP&&state.control().continuation()==null)management.add("start"); }
        }
        if(!row.paused())for(String actor:owned(id,user)) {
            var controls=runtime.controls(state,actor);var entities=controls.contains("activate")?runtime.eligibleEntities(state,actor):List.<String>of();
            actors.add(new ActorActions(actor,runtime.candidates(state,actor),controls,entities,runtime.offeredActions(state,actor)));
        }
        return new Choices(state.version(),row.paused(),management,actors);
    }
    public Map<String,Object> preview(UUID id,JsonNode request,MetisUser user) {
        shape(request,Set.of("actor","action","parameters","expectedStateVersion"));String actor=text(request,"actor");
        var row=session(id,user,false);check(owned(id,user).contains(actor),403,"ACTOR_NOT_OWNED");
        check(integer(request,"expectedStateVersion")==row.version(),409,"STALE_STATE");var runtime=runtime(row);
        if(row.paused())return Map.of("allowed",false,"code","SESSION_PAUSED","version",row.version());
        try {
            var result=runtime.preview(runtime.restore(row.checkpoint()),actor,text(request,"action"),parameters(runtime,request));
            var declaration=runtime.definition().actions().get(text(request,"action"));
            return Map.of("allowed",result.allowed(),"code",result.code(),"version",row.version(),"movements",result.movements(),"resources",result.resources(),"scope",declaration!=null&&declaration.movementPreview()?"MOVEMENT":"ELIGIBILITY");
        }catch(IllegalArgumentException invalid){return Map.of("allowed",false,"code","VALUE_TYPE","version",row.version());}
    }
    @Transactional public void delete(UUID id,MetisUser user) { var row=session(id,user,true);check(row.owner()==user.userId(),403,"OWNER_REQUIRED");repository.delete(id); }

    @Transactional public StoredResponse command(UUID id,JsonNode request,MetisUser user) {
        return command(id,request,user,false);
    }
    private StoredResponse command(UUID id,JsonNode request,MetisUser user,boolean cpu) {
        check(request!=null&&request.isObject()&&request.toString().length()<=1_000_000,400,"COMMAND_INVALID");
        String type=text(request,"type"),command=text(request,"commandId");long expected=integer(request,"expectedStateVersion");
        check(command.matches("[a-zA-Z0-9_.:-]{1,128}")&&expected>=0,400,"COMMAND_INVALID");
        var fields=new HashSet<>(Set.of("type","commandId","expectedStateVersion"));boolean manager=Set.of("start","pause","resume").contains(type);
        if(!manager)fields.add("actor");
        switch(type) {
            case "act" -> fields.addAll(Set.of("action","parameters"));
            case "respond" -> fields.addAll(Set.of("decisionId","option"));
            case "activate" -> fields.add("entityId");
            case "start","pause","resume","pass","advanceStep" -> { }
            default -> throw new Failure(400,"COMMAND_UNKNOWN");
        }
        shape(request,fields);String actor=manager?"@owner":text(request,"actor");var row=session(id,user,true);
        check(manager?row.owner()==user.userId():cpu?repository.seats(id).stream().anyMatch(s->s.kind().equals("CPU")&&s.playerId().equals(actor)):owned(id,user).contains(actor),403,manager?"OWNER_REQUIRED":"ACTOR_NOT_OWNED");
        String hash=digest(canonical(request));var prior=repository.receipt(id,user.userId(),actor,command);
        if(prior.isPresent()) { check(prior.get().hash().equals(hash),409,"IDEMPOTENCY_KEY_REUSED");return new StoredResponse(prior.get().status(),prior.get().response()); }
        var runtime=runtime(row);var state=runtime.restore(row.checkpoint());RulesState.Result result;boolean paused=row.paused();
        if(expected!=state.version())result=rejected("STALE_STATE");
        else if(type.equals("pause")||type.equals("resume")) {
            boolean next=type.equals("pause");
            if(state.control().status()==RulesState.Status.FINISHED||paused==next)result=rejected("LIFECYCLE_INVALID");
            else if(state.events().size()>=100000)result=rejected("EVENT_LIMIT");
            else { result=lifecycle(state,next);paused=next; }
        } else if(paused)result=rejected("SESSION_PAUSED");
        else {
            try {
                result=switch(type) {
                    case "start" -> runtime.start(state,expected);
                    case "act" -> runtime.act(state,actor,text(request,"action"),parameters(runtime,request),expected);
                    case "respond" -> runtime.respond(state,actor,text(request,"decisionId"),Math.toIntExact(integer(request,"option")),expected);
                    case "activate" -> runtime.activate(state,actor,text(request,"entityId"),expected);
                    case "advanceStep" -> runtime.advanceStep(state,actor,expected);
                    default -> runtime.pass(state,actor,expected);
                };
            }catch(IllegalArgumentException|ArithmeticException error){result=rejected("VALUE_TYPE");}
        }
        if(cpu&&result.accepted())result=appendEvent(result,"rules.cpuCommand",actor,Map.of("type",RuleValue.text(type)));
        int status=result.accepted()?200:result.code().equals("STALE_STATE")?409:422;
        var visible=view(row,runtime,result.accepted()?result.state():state,paused,user);
        String response=write(new CommandResponse(result.accepted(),result.code(),visible));
        repository.save(id,state.version(),result.accepted()?RulesStateCodec.encode(result.state()):null,result.accepted()?RulesStateCodec.encode(result.commit()):null,paused,user.userId(),actor,command,hash,response,status);
        if(result.accepted()&&(cpu||type.equals("resume")))repository.cpuTouch(id,cpu?1:0,type.equals("resume"));
        return new StoredResponse(status,response);
    }
    /** Trusted scheduled entry point. Policy receives only one CPU seat's projection. */
    @Transactional public void advanceCpu(UUID id) {
        var selected=repository.claimCpu(id);if(selected.isEmpty())return;var row=selected.get();repository.cpuTouch(id,0,false);
        var seats=repository.seats(id).stream().filter(s->s.kind().equals("CPU")).toList();if(seats.isEmpty())return;
        RulesRuntime runtime;RulesState state;
        try { runtime=runtime(row);check(runtime.version().equals(RulesRuntime.VERSION),409,"CPU_REQUIRES_PROJECTED_RUNTIME");state=runtime.restore(row.checkpoint()); }
        catch(RuntimeException incompatible) { repository.quarantineCpu(id,"SNAPSHOT_INCOMPATIBLE");return; }
        if(row.cpuSteps()>=row.cpuLimit()) { pauseCpu(row,runtime,state,"CPU_COMMAND_LIMIT");return; }
        var policy=new RulesCpu();RulesCpu.Command chosen=null;boolean waiting=false;
        try {
            for(var seat:seats) {
                String actor=seat.playerId();var input=runtime.decisionInput(state,actor);var candidate=policy.choose(input);
                if(candidate.isPresent()) { chosen=candidate.get();break; }
                var flow=state.control().flow();
                waiting|=state.control().decision()!=null?state.control().decision().player().equals(actor):state.control().status()==RulesState.Status.RUNNING&&(flow.playerIndex()<0?!flow.completedPlayers().contains(actor):state.players().get(flow.playerIndex()).equals(actor));
            }
        }catch(RuleEvaluationException|IllegalArgumentException invalid) { pauseCpu(row,runtime,state,"CPU_NO_COMMAND");return; }
        if(chosen==null) { if(waiting)pauseCpu(row,runtime,state,"CPU_NO_COMMAND");return; }
        var request=json.createObjectNode().put("type",chosen.type()).put("actor",chosen.actor()).put("commandId","cpu-v"+state.version()).put("expectedStateVersion",state.version());
        switch(chosen.type()) {
            case "act" -> { request.put("action",chosen.action());var parameters=request.putObject("parameters");chosen.parameters().forEach((k,v)->parameters.set(k,literal(v))); }
            case "respond" -> request.put("decisionId",chosen.decision()).put("option",chosen.option());
            case "activate" -> request.put("entityId",chosen.entity());
            default -> { }
        }
        var principal=new MetisUser(row.owner(),"rules-cpu","Rules CPU","player");
        StoredResponse response;
        try { response=command(id,request,principal,true); }
        catch(RuleEvaluationException limited) { pauseCpu(row,runtime,state,"CPU_EXECUTION_LIMIT");return; }
        if(response.status()!=200)pauseCpu(row,runtime,state,"CPU_ACTION_REJECTED");
    }
    private JsonNode literal(RuleValue value) {
        if(value.type().kind()==RuleType.Kind.ENTITY)return json.getNodeFactory().textNode(((RuleValue.EntityRef)value.value()).id());
        if(value.type().kind()==RuleType.Kind.LIST) { var array=json.createArrayNode();value.items().forEach(v->array.add(literal(v)));return array; }
        return json.valueToTree(value.value());
    }
    private void pauseCpu(RulesRepository.Session row,RulesRuntime runtime,RulesState state,String reason) {
        if(state.events().size()>=100000) { repository.quarantineCpu(row.id(),"CPU_EVENT_LIMIT");return; }
        var result=lifecycle(state,true,reason);var principal=new MetisUser(row.owner(),"rules-cpu","Rules CPU","player");
        var response=write(new CommandResponse(true,reason,view(row,runtime,result.state(),true,principal)));
        repository.save(row.id(),state.version(),RulesStateCodec.encode(result.state()),RulesStateCodec.encode(result.commit()),true,row.owner(),"@owner","cpu-pause-v"+state.version(),digest(reason),response,200);
    }
    private static RulesState.Result appendEvent(RulesState.Result result,String type,String actor,Map<String,RuleValue> values) {
        if(result.state().events().size()>=100000)throw new RuleEvaluationException("EVENT_LIMIT","","Session event limit reached");
        var old=result.commit();var events=new ArrayList<>(old.events());events.add(new RulesState.Event(result.state().events().size()+1L,type,actor,CompiledRules.Audience.PUBLIC,null,values,null));
        var commit=new RulesState.Commit(old.runtimeVersion(),old.definitionFingerprint(),old.sessionId(),old.beforeVersion(),old.version(),old.globals(),old.entities(),old.removedEntities(),old.control(),events,old.trace());
        var all=new ArrayList<>(result.state().events());all.add(events.getLast());var s=result.state();var next=new RulesState(s.runtimeVersion(),s.definitionFingerprint(),s.sessionId(),s.version(),s.players(),s.globals(),s.entities(),s.control(),all);
        return new RulesState.Result(true,result.code(),result.message(),next,commit);
    }
    private Map<String,RuleValue> parameters(RulesRuntime runtime,JsonNode request) {
        var declaration=runtime.definition().actions().get(text(request,"action"));if(declaration==null)return Map.of();
        var parameters=request.get("parameters");check(parameters.isObject(),400,"COMMAND_INVALID");
        if(!keys(parameters).equals(declaration.parameters().keySet()))throw new IllegalArgumentException("Action inputs differ from declaration");
        var values=new TreeMap<String,RuleValue>();declaration.parameters().forEach((name,type)->values.put(name,RulesStateCodec.literal(type,parameters.get(name))));return values;
    }
    private static RulesState.Result rejected(String code) { return new RulesState.Result(false,code,"Command rejected",null,null); }
    private static RulesState.Result lifecycle(RulesState state,boolean paused) {
        return lifecycle(state,paused,null);
    }
    private static RulesState.Result lifecycle(RulesState state,boolean paused,String reason) {
        var event=new RulesState.Event(state.events().size()+1L,paused?"rules.paused":"rules.resumed",null,CompiledRules.Audience.PUBLIC,null,reason==null?Map.of():Map.of("reason",RuleValue.text(reason)),null);
        var commit=new RulesState.Commit(state.runtimeVersion(),state.definitionFingerprint(),state.sessionId(),state.version(),Math.incrementExact(state.version()),Map.of(),Map.of(),Set.of(),state.control(),List.of(event),List.of());
        var next=RulesRuntime.replay(state,commit);RulesStateCodec.encode(next);return new RulesState.Result(true,"ACCEPTED","Lifecycle updated",next,commit);
    }
    private static Set<String> keys(JsonNode node) { var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys; }
    private static void shape(JsonNode node,Set<String> fields) { check(node!=null&&node.isObject()&&keys(node).equals(fields),400,"REQUEST_FIELDS_INVALID"); }
    private static String text(JsonNode node,String field) { check(node.hasNonNull(field)&&node.get(field).isTextual()&&!node.get(field).asText().isBlank(),400,"REQUEST_FIELD_INVALID");return node.get(field).textValue(); }
    private static long integer(JsonNode node,String field) { check(node.hasNonNull(field)&&node.get(field).isIntegralNumber()&&node.get(field).canConvertToLong(),400,"REQUEST_FIELD_INVALID");return node.get(field).longValue(); }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception error){throw new Failure(500,"SERIALIZATION_FAILED");} }
    private String canonical(JsonNode node) {
        if(node.isObject())return "{"+keys(node).stream().map(key->write(key)+":"+canonical(node.get(key))).collect(java.util.stream.Collectors.joining(","))+"}";
        if(node.isArray()) { var items=new ArrayList<String>();node.forEach(value->items.add(canonical(value)));return "["+String.join(",",items)+"]"; }
        if(node.isFloatingPointNumber()) {
            var value=node.decimalValue().stripTrailingZeros();check(value.precision()<=34&&Math.abs((long)value.scale())<=128,400,"NUMERIC_LIMIT");
            String text=value.toPlainString();return text.contains(".")?text:text+".0";
        }
        return node.toString();
    }
    public JsonNode parse(String source) {
        check(source!=null&&source.length()<=1_000_000,400,"REQUEST_TOO_LARGE");
        try { var value=INPUT.readTree(source);check(value!=null&&value.isObject(),400,"REQUEST_INVALID");canonical(value);return value; }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new Failure(400,"JSON_INVALID");}
    }
    private JsonNode resolution(String source) {
        // PostgreSQL jsonb adds whitespace to compact, publication-bounded metadata.
        check(source!=null&&source.length()<=2_000_000,409,"SNAPSHOT_INCOMPATIBLE");
        try { var value=INPUT.readTree(source);check(value!=null&&value.isObject(),409,"SNAPSHOT_INCOMPATIBLE");return value; }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new Failure(409,"SNAPSHOT_INCOMPATIBLE"); }
    }
    public long editVersion(JsonNode body,boolean withSource) { shape(body,withSource?Set.of("expectedVersion","source"):Set.of("expectedVersion"));long version=integer(body,"expectedVersion");check(version>=0,400,"REQUEST_FIELD_INVALID");return version; }
    private static String digest(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);} }
}
