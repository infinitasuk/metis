package com.metis.engine.core.rules;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Standalone schema + semantic compiler. It never executes procedures or changes a game. */
public final class RulesCompiler {
    public static final String VERSION="rules-compiler-5";
    public static final String MECHANICS_VERSION="rules-compiler-4";
    public static final String BASE_VERSION="rules-compiler-3";
    public static boolean supportsVersion(String version) { return VERSION.equals(version)||MECHANICS_VERSION.equals(version)||BASE_VERSION.equals(version); }
    private static final ObjectMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(256).maxStringLength(4096).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private static final JsonSchema SCHEMA=loadSchema();
    private final OperationRegistry registry;
    public record Result(CompiledRules definition,List<RuleDiagnostic> diagnostics) {
        public Result { diagnostics=List.copyOf(diagnostics); }
        public boolean valid() { return definition!=null && diagnostics.isEmpty(); }
    }
    public RulesCompiler() { this(OperationRegistry.standard()); }
    public RulesCompiler(OperationRegistry registry) { this.registry=Objects.requireNonNull(registry); }

    public Result compile(String source) {
        if(source==null || source.length()>1_000_000)return invalid("SOURCE_LIMIT",null,"","A definition of at most 1,000,000 characters is required");
        JsonNode root;
        try { root=JSON.readTree(source); }
        catch(JsonProcessingException exception) { return invalid("JSON_SYNTAX",null,"",exception.getOriginalMessage()); }
        if(root==null || !root.isObject())return invalid("SCHEMA_INVALID",null,"","Definition must be an object");
        String id=root.path("id").isTextual()?root.get("id").textValue():null;
        if(!root.path("languageVersion").isIntegralNumber() || !root.get("languageVersion").bigIntegerValue().equals(java.math.BigInteger.ONE))
            return invalid("LANGUAGE_VERSION",id,"/languageVersion","Only rules language version 1 is supported");
        var structural=SCHEMA.validate(root);
        if(!structural.isEmpty())return new Result(null,structural.stream().map(message->diagnostic("SCHEMA_INVALID",id,message.getInstanceLocation().toString(),message.getMessage()))
                .sorted(Comparator.comparing(RuleDiagnostic::path).thenComparing(RuleDiagnostic::message)).limit(100).toList());
        try { return new Result(compileDefinition(root),List.of()); }
        catch(CompileScope.Invalid invalid) { return new Result(null,List.of(invalid.diagnostic)); }
    }
    private static JsonSchema loadSchema() {
        try(var input=RulesCompiler.class.getResourceAsStream("/rules/language-v1.schema.json")) {
            if(input==null)throw new IllegalStateException("Packaged rules schema is missing");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(input,
                    SchemaValidatorsConfig.builder().pathType(PathType.JSON_POINTER).locale(Locale.ENGLISH).build());
        } catch(java.io.IOException error) { throw new IllegalStateException("Cannot load packaged schema",error); }
    }
    private CompiledRules compileDefinition(JsonNode root) {
        var bounds=root.get("limits");
        var limits=new CompiledRules.Limits(bounds.get("maxInstructions").intValue(),bounds.get("maxCollectionSize").intValue(),bounds.get("maxCallDepth").intValue(),bounds.path("maxWindowDepth").asInt(8));
        var scope=new CompileScope(root.get("id").textValue(),limits,CompileScope.keys(root.get("entities")));
        var modules=new TreeMap<String,String>();
        for(String module:CompileScope.keys(root.get("modules"))) {
            String version=root.get("modules").get(module).textValue();var available=registry.modules().get(module);
            scope.expect(available!=null,"MODULE_UNKNOWN","/modules/"+module,"Unsupported module "+module);
            scope.expect(available.version().equals(version),"MODULE_VERSION","/modules/"+module,"Unsupported module version "+version);modules.put(module,version);
        }
        scope.expect(modules.containsKey("core"),"MODULE_REQUIRED","/modules/core","The core module must be pinned");
        scope.expect(!modules.containsKey("maneuver")||modules.containsKey("space"),"MODULE_REQUIRED","/modules/maneuver","Maneuvers require the spatial module");
        scope.state.putAll(fields(scope,root.get("state"),"/state",false));
        for(String name:scope.entityNames.stream().sorted().toList()) {
            String path="/entities/"+name;var entity=root.get("entities").get(name);
            var ownership=CompiledRules.Ownership.valueOf(entity.get("ownership").textValue());
            var attributes=fields(scope,entity.get("attributes"),path+"/attributes",ownership==CompiledRules.Ownership.PLAYER);
            var resources=new TreeMap<String,CompiledRules.Resource>();
            for(String resource:CompileScope.keys(entity.get("resources"))) {
                String at=path+"/resources/"+resource;var item=entity.get("resources").get(resource);
                long initial=item.get("initial").longValue(),min=item.get("minimum").longValue(),max=item.get("maximum").longValue();
                scope.expect(min<=initial&&initial<=max,"RESOURCE_BOUNDS",at,"Resource initial value must be within its bounds");
                var audience=audience(scope,item.get("visibility"),ownership==CompiledRules.Ownership.PLAYER,at+"/visibility");
                resources.put(resource,new CompiledRules.Resource(initial,min,max,audience));
            }
            scope.entities.put(name,new CompiledRules.EntityType(ownership,attributes,resources));
        }
        for(String event:CompileScope.keys(root.get("events"))) {
            var node=root.get("events").get(event);scope.events.put(event,new CompiledRules.EventType(CompiledRules.Audience.valueOf(node.get("visibility").textValue()),scope.parameters(node.get("fields"),"/events/"+event+"/fields")));
        }
        for(String name:CompileScope.keys(root.get("procedures")))scope.procedures.put(name,scope.parameters(root.get("procedures").get(name).get("parameters"),"/procedures/"+name+"/parameters"));
        for(String name:CompileScope.keys(root.path("windows")))scope.windows.put(name,scope.parameters(root.get("windows").get(name).get("parameters"),"/windows/"+name+"/parameters"));
        var compiler=new InstructionCompiler(scope,registry,modules.keySet());
        for(var entry:scope.windows.entrySet()) {
            String path="/windows/"+entry.getKey();var window=root.get("windows").get(entry.getKey());
            var env=scope.environment(entry.getValue(),path+"/parameters");
            var responders=compiler.expression(window.get("responders"),env,path+"/responders");scope.type(RuleType.list(RuleType.PLAYER),responders,responders.path());
            scope.bind(env,"responder",RuleType.PLAYER,path+"/parameters/responder");
            int max=window.get("maxResponders").intValue();scope.expect(max<=limits.maxCollectionSize(),"WINDOW_BOUND",path+"/maxResponders","Responder bound exceeds collection limit");
            var ids=new HashSet<String>();var choices=new ArrayList<CompiledRules.Reaction>();
            for(int i=0;i<window.get("choices").size();i++) {
                var choice=window.get("choices").get(i);String at=path+"/choices/"+i,id=choice.get("id").textValue();
                scope.expect(ids.add(id),"REACTION_DUPLICATE",at+"/id","Duplicate reaction");
                var condition=compiler.expression(choice.get("when"),env,at+"/when");scope.type(RuleType.BOOLEAN,condition,condition.path());
                String target=choice.get("procedure").textValue();choices.add(new CompiledRules.Reaction(id,condition,target,
                        compiler.arguments(choice.get("args"),compiler.procedure(target,at+"/procedure"),env,at+"/args")));
            }
            scope.compiledWindows.put(entry.getKey(),new CompiledRules.Window(entry.getValue(),responders,max,window.get("allowPass").booleanValue(),CompiledRules.Audience.valueOf(window.get("visibility").textValue()),choices));
        }
        var procedures=new TreeMap<String,CompiledRules.Procedure>();
        for(var signature:scope.procedures.entrySet()) {
            String path="/procedures/"+signature.getKey();
            var body=compiler.steps(root.get("procedures").get(signature.getKey()).get("steps"),scope.environment(signature.getValue(),path+"/parameters"),path+"/steps");
            procedures.put(signature.getKey(),new CompiledRules.Procedure(signature.getValue(),body,0));
        }
        var measures=new HashMap<String,Measure>();
        for(String name:procedures.keySet())measure(name,procedures,scope,measures,new HashSet<>(),"/procedures/"+name);
        procedures.replaceAll((name,procedure)->new CompiledRules.Procedure(procedure.parameters(),procedure.steps(),measures.get(name).instructions));
        String setup=root.get("setup").textValue();
        scope.expect(compiler.procedure(setup,"/setup").isEmpty(),"ARGUMENT_COUNT","/setup","Setup procedure cannot require arguments");
        var actions=new TreeMap<String,CompiledRules.Action>();
        for(String name:CompileScope.keys(root.get("actions"))) {
            String path="/actions/"+name;var action=root.get("actions").get(name);var parameters=scope.parameters(action.get("parameters"),path+"/parameters");
            var env=scope.environment(parameters,path+"/parameters");scope.bind(env,"actor",RuleType.PLAYER,path+"/parameters/actor");
            var available=compiler.expression(action.get("availableWhen"),env,path+"/availableWhen");scope.type(RuleType.BOOLEAN,available,available.path());
            String procedure=action.get("procedure").textValue();
            var choiceEnv=scope.environment(Map.of(),path);scope.bind(choiceEnv,"actor",RuleType.PLAYER,path);var choices=new TreeMap<String,Expression>();
            for(String parameter:CompileScope.keys(action.path("choices"))) {
                scope.expect(parameters.containsKey(parameter),"CHOICE_PARAMETER",path+"/choices/"+parameter,"Unknown input parameter");
                var expression=compiler.expression(action.get("choices").get(parameter),choiceEnv,path+"/choices/"+parameter);scope.type(RuleType.list(parameters.get(parameter)),expression,expression.path());choices.put(parameter,expression);
                scope.bind(choiceEnv,parameter,parameters.get(parameter),path+"/choices/"+parameter);
            }
            boolean movement=action.has("preview");if(movement)validateMovementPreview(procedure,procedures,registry,scope,new HashSet<>());
            CompiledRules.ManeuverControl maneuver=null;
            if(action.has("maneuver")) {
                var hint=action.get("maneuver");String at=path+"/maneuver",entity=hint.get("entity").textValue(),speed=hint.get("speed").textValue(),yaw=hint.get("yaw").textValue(),side=hint.get("side").textValue();
                scope.expect(modules.containsKey("maneuver")&&movement,"MANEUVER_CONTROL",at,"Maneuver controls require the module and movement preview");
                scope.expect(new HashSet<>(List.of(entity,speed,yaw,side)).size()==4&&parameters.containsKey(entity)&&parameters.get(entity).kind()==RuleType.Kind.ENTITY
                        &&RuleType.INTEGER.equals(parameters.get(speed))&&RuleType.list(RuleType.INTEGER).equals(parameters.get(yaw))&&RuleType.STRING.equals(parameters.get(side)),
                        "MANEUVER_CONTROL",at,"Bind distinct entity, integer speed, integer-list yaw and string side parameters");
                var hintEnv=scope.environment(Map.of(entity,parameters.get(entity)),at);scope.bind(hintEnv,"actor",RuleType.PLAYER,at);
                var chart=compiler.expression(hint.get("chart"),hintEnv,at+"/chart");scope.type(RuleType.list(RuleType.list(RuleType.INTEGER)),chart,chart.path());
                maneuver=new CompiledRules.ManeuverControl(entity,speed,yaw,side,chart);
            }
            actions.put(name,new CompiledRules.Action(parameters,available,procedure,compiler.arguments(action.get("args"),compiler.procedure(procedure,path+"/procedure"),env,path+"/args"),choices,action.path("priority").asInt(0),movement,maneuver));
        }
        var flow=flow(scope,compiler,root.get("flow"),actions);
        var mechanics=MechanicsCompiler.compile(root,scope,modules.keySet());
        var space=SpatialCompiler.compile(root,scope,modules.keySet());var scenario=SpatialCompiler.scenario(root,scope,modules.keySet());
        boolean spatial=modules.containsKey("space")||modules.containsKey("scenario")||actions.values().stream().anyMatch(a->!a.choices().isEmpty()||a.priority()!=0||a.movementPreview());
        String version=spatial?VERSION:modules.keySet().stream().anyMatch(MechanicsRules.MODULES::contains)?MECHANICS_VERSION:BASE_VERSION;
        String canonical=canonical(root);
        String fingerprint=hash(version+"\n"+canonical+"\n"+String.join("\n",registry.contracts(modules.keySet())));
        return new CompiledRules(scope.id,root.get("revision").textValue(),version,fingerprint,canonical,modules,limits,scope.state,scope.entities,scope.events,scope.compiledWindows,procedures,actions,flow,setup,mechanics,space,scenario);
    }
    private static boolean validateMovementPreview(String procedure,Map<String,CompiledRules.Procedure> procedures,OperationRegistry registry,CompileScope scope,Set<String> visited) {
        scope.expect(procedures.containsKey(procedure),"PROCEDURE_UNKNOWN","/procedures/"+procedure,"Unknown procedure");
        if(!visited.add(procedure))return false;
        boolean move=movementSteps(procedures.get(procedure).steps(),procedures,registry,scope,visited);
        scope.expect(move,"MOVEMENT_PREVIEW","/procedures/"+procedure,"Movement preview requires a movement operation");return move;
    }
    private static boolean movementSteps(List<CompiledRules.Instruction> steps,Map<String,CompiledRules.Procedure> procedures,OperationRegistry registry,CompileScope scope,Set<String> visited) {
        boolean movement=false;
        for(var step:steps) {
            var op=registry.operation(step.operation());
            scope.expect(!op.effects().contains(OperationRegistry.Effect.RANDOM)&&!op.effects().contains(OperationRegistry.Effect.DECISION),"MOVEMENT_PREVIEW",step.path(),"Movement previews cannot include random operations or decisions");
            if(step.operation().equals("space.move")||step.operation().equals("maneuver.move"))movement=true;
            if(step.operation().equals("core.call")&&!visited.contains(step.references().get("procedure"))) {
                String callee=step.references().get("procedure");visited.add(callee);movement|=movementSteps(procedures.get(callee).steps(),procedures,registry,scope,visited);
            }
            for(var block:step.blocks().values())movement|=movementSteps(block,procedures,registry,scope,visited);
        }
        return movement;
    }
    private static Map<String,CompiledRules.Field> fields(CompileScope scope,JsonNode node,String path,boolean owned) {
        var fields=new TreeMap<String,CompiledRules.Field>();
        for(String name:CompileScope.keys(node)) {
            String at=path+"/"+name;var field=node.get(name);var type=scope.type(field.get("type"),at+"/type");
            fields.put(name,new CompiledRules.Field(type,scope.initial(field.get("initial"),type,at+"/initial"),audience(scope,field.get("visibility"),owned,at+"/visibility")));
        }
        return fields;
    }
    private static CompiledRules.Audience audience(CompileScope scope,JsonNode node,boolean owned,String path) {
        var audience=CompiledRules.Audience.valueOf(node.textValue());scope.expect(owned||audience!=CompiledRules.Audience.OWNER,"AUDIENCE_INVALID",path,"OWNER fields require a player-owned entity");return audience;
    }
    private static CompiledRules.Flow flow(CompileScope scope,InstructionCompiler compiler,JsonNode node,Map<String,CompiledRules.Action> actions) {
        var phases=new TreeMap<String,CompiledRules.Phase>();var names=CompileScope.keys(node.get("phases"));
        for(String name:names) {
            String path="/flow/phases/"+name;var phase=node.get("phases").get(name);var controller=CompiledRules.Controller.valueOf(phase.get("controller").textValue());
            String next=phase.get("next").textValue();scope.expect(names.contains(next),"PHASE_UNKNOWN",path+"/next","Unknown next phase "+next);
            String onEnter=phase.has("onEnter")?phase.get("onEnter").textValue():null;
            if(onEnter!=null)scope.expect(compiler.procedure(onEnter,path+"/onEnter").isEmpty(),"ARGUMENT_COUNT",path+"/onEnter","Phase entry procedure cannot require arguments");
            boolean entityPhase=controller==CompiledRules.Controller.ALTERNATING_ENTITIES||controller==CompiledRules.Controller.PLAYER_PHASE;
            scope.expect(phase.has("entities")==entityPhase,"PHASE_ENTITIES",path+"/entities","Entity controllers require exactly one entity selector");
            Expression entities=null;
            if(entityPhase) {
                var env=scope.environment(Map.of(),path);scope.bind(env,"actor",RuleType.PLAYER,path);
                entities=compiler.expression(phase.get("entities"),env,path+"/entities");
                scope.expect(entities.type().kind()==RuleType.Kind.LIST&&entities.type().element().kind()==RuleType.Kind.ENTITY,"TYPE_MISMATCH",path+"/entities","Phase selector must return entity references");
            }
            scope.expect(phase.has("entityParameter")==entityPhase,"PHASE_SUBJECT",path+"/entityParameter","Entity controllers require an explicit action subject parameter");
            String subject=entityPhase?phase.get("entityParameter").textValue():null;
            int group=phase.get("groupSize").intValue();scope.expect(group<=scope.limits.maxCollectionSize()&&(entityPhase||group==1),"GROUP_SIZE",path+"/groupSize","Invalid group size for controller");
            var steps=new ArrayList<CompiledRules.Step>();var ids=new HashSet<String>();
            for(int i=0;i<phase.get("steps").size();i++) {
                var step=phase.get("steps").get(i);String at=path+"/steps/"+i;String id=step.get("id").textValue();
                scope.expect(ids.add(id),"STEP_DUPLICATE",at+"/id","Duplicate step identifier");
                int min=step.get("minimum").intValue(),max=step.get("maximum").intValue();scope.expect(min<=max&&max<=scope.limits.maxInstructions(),"ACTION_BUDGET",at,"Invalid action budget");
                var permitted=new ArrayList<String>();for(int j=0;j<step.get("actions").size();j++) {
                    String action=step.get("actions").get(j).textValue();scope.expect(actions.containsKey(action),"ACTION_UNKNOWN",at+"/actions/"+j,"Unknown action "+action);
                     if(entityPhase)scope.expect(actions.get(action).parameters().containsKey(subject)&&actions.get(action).parameters().get(subject).accepts(entities.type().element()),"PHASE_SUBJECT",at+"/actions/"+j,"Action must declare the phase's typed entity subject");
                     if(actions.get(action).maneuver()!=null)scope.expect(entityPhase&&actions.get(action).maneuver().entity().equals(subject),"MANEUVER_CONTROL",at+"/actions/"+j,"Maneuver controls bind the active entity subject");
                    permitted.add(action);
                }
                steps.add(new CompiledRules.Step(id,permitted,min,max));
            }
            scope.expect(controller!=CompiledRules.Controller.AUTOMATIC||steps.isEmpty(),"AUTOMATIC_ACTIONS",path+"/steps","Automatic phases execute entry procedures, not player actions");
            scope.expect(controller==CompiledRules.Controller.AUTOMATIC||controller==CompiledRules.Controller.MANUAL||!steps.isEmpty(),"PHASE_EMPTY",path+"/steps","Player/entity phase requires action steps");
            phases.put(name,new CompiledRules.Phase(controller,group,entities,subject,steps,onEnter,next));
        }
        String initial=node.get("initial").textValue(),round=node.get("roundStart").textValue();
        scope.expect(names.contains(initial),"PHASE_UNKNOWN","/flow/initial","Unknown initial phase");scope.expect(names.contains(round),"PHASE_UNKNOWN","/flow/roundStart","Unknown round start phase");
        var reached=new HashSet<String>();String cursor=initial;
        while(reached.add(cursor))cursor=phases.get(cursor).next();
        scope.expect(reached.equals(names),"PHASE_UNREACHABLE","/flow/phases","Every phase must be reachable from initial phase");
        var cycle=new HashSet<String>();while(cycle.add(cursor))cursor=phases.get(cursor).next();
        scope.expect(cycle.contains(round),"ROUND_START_UNREACHABLE","/flow/roundStart","Round start must lie on the repeating phase cycle");
        for(String start:names) {
            var automatic=new HashSet<String>();cursor=start;
            while(phases.get(cursor).controller()==CompiledRules.Controller.AUTOMATIC) {
                scope.expect(automatic.add(cursor),"AUTOMATIC_CYCLE","/flow/phases/"+cursor+"/next","Automatic-only phase cycles are not allowed");cursor=phases.get(cursor).next();
            }
        }
        return new CompiledRules.Flow(initial,round,phases);
    }
    private record Measure(long instructions,int depth,int windowDepth) { }
    private static Measure measure(String name,Map<String,CompiledRules.Procedure> procedures,CompileScope scope,Map<String,Measure> cache,Set<String> active,String path) {
        scope.expect(!active.contains(name),"PROCEDURE_RECURSION",path,"Recursive procedure dependency "+name);
        if(cache.containsKey(name))return cache.get(name);
        active.add(name);var measured=measureSteps(procedures.get(name).steps(),procedures,scope,cache,active);active.remove(name);
        scope.expect(measured.depth<=scope.limits.maxCallDepth(),"CALL_DEPTH",path,"Procedure call-depth limit exceeded");
        scope.expect(measured.windowDepth<=scope.limits.maxWindowDepth(),"WINDOW_DEPTH",path,"Reaction nesting limit exceeded");
        scope.expect(measured.instructions<=scope.limits.maxInstructions(),"INSTRUCTION_LIMIT",path,"Expanded procedure exceeds instruction budget");cache.put(name,measured);return measured;
    }
    private static Measure measureSteps(List<CompiledRules.Instruction> steps,Map<String,CompiledRules.Procedure> procedures,CompileScope scope,Map<String,Measure> cache,Set<String> active) {
        long total=0;int depth=1,windowDepth=0;
        for(var step:steps) {
            long size=1;
            if(step.operation().equals("core.call")) {
                var callee=measure(step.references().get("procedure"),procedures,scope,cache,active,step.path()+"/procedure");size+=callee.instructions;depth=Math.max(depth,callee.depth+1);windowDepth=Math.max(windowDepth,callee.windowDepth);
            } else if(step.operation().equals("core.window")) {
                var window=scope.compiledWindows.get(step.references().get("window"));long maximum=0;windowDepth=Math.max(windowDepth,1);
                for(var choice:window.choices()) {
                    var callee=measure(choice.procedure(),procedures,scope,cache,active,step.path()+"/window");maximum=Math.max(maximum,callee.instructions);
                    depth=Math.max(depth,callee.depth+1);windowDepth=Math.max(windowDepth,callee.windowDepth+1);
                }
                size+=window.maxResponders()*(maximum+1);
            } else if(!step.blocks().isEmpty()) {
                long blockSize=0;
                for(var block:step.blocks().values()) { var child=measureSteps(block,procedures,scope,cache,active);blockSize=Math.max(blockSize,child.instructions);depth=Math.max(depth,child.depth);windowDepth=Math.max(windowDepth,child.windowDepth); }
                size+=blockSize*(step.operation().equals("core.forEach")?Long.parseLong(step.references().get("maxItems")):1);
            }
            total+=size;scope.expect(total<=scope.limits.maxInstructions(),"INSTRUCTION_LIMIT",step.path(),"Expanded procedure exceeds instruction budget");
        }
        return new Measure(total,depth,windowDepth);
    }
    private static String canonical(JsonNode node) {
        if(node.isObject())return "{"+CompileScope.keys(node).stream().map(key->quote(key)+":"+canonical(node.get(key))).collect(java.util.stream.Collectors.joining(","))+"}";
        if(node.isArray()) { var items=new ArrayList<String>();node.forEach(item->items.add(canonical(item)));return "["+String.join(",",items)+"]"; }
        if(node.isFloatingPointNumber()) { var number=node.decimalValue().stripTrailingZeros();String value=number.toPlainString();return value.contains(".")?value:value+".0"; }
        return node.toString();
    }
    private static String quote(String value) { try{return JSON.writeValueAsString(value);}catch(JsonProcessingException error){throw new IllegalStateException(error);} }
    private static String hash(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);} }
    private static RuleDiagnostic diagnostic(String code,String id,String path,String message) {
        return new CompileScope(id,new CompiledRules.Limits(1,1,1),Set.of()).error(code,path,message).diagnostic;
    }
    private static Result invalid(String code,String id,String path,String message) { return new Result(null,List.of(diagnostic(code,id,path,message))); }
}
