package com.metis.engine.core.rules;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

/** Pure, bounded single-parent composition. Lookup is supplied by the authoring boundary. */
public final class RulesResolver {
    public static final String VERSION="rules-resolver-1";
    private static final ObjectMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(256).maxStringLength(4096).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    public record Reference(String id,String revision) { }
    public record Layer(Reference reference,String sourceHash,String fingerprint) { }
    public record Change(Reference author,String operation,String path) { }
    public record Resolution(String resolverVersion,List<Layer> layers,Map<String,Reference> origins,List<Change> changes) {
        public Resolution { layers=List.copyOf(layers);origins=Collections.unmodifiableMap(new TreeMap<>(origins));changes=List.copyOf(changes); }
    }
    public record Result(CompiledRules definition,Resolution resolution,List<RuleDiagnostic> diagnostics) {
        public Result { diagnostics=List.copyOf(diagnostics); }
        public boolean valid() { return definition!=null&&diagnostics.isEmpty(); }
    }
    private static final class Invalid extends RuntimeException {
        final RuleDiagnostic diagnostic;
        Invalid(String code,String id,String path,String message) { diagnostic=new RuleDiagnostic(code,id,null,path,message); }
    }
    private final RulesCompiler compiler;
    public RulesResolver() { this(new RulesCompiler()); }
    public RulesResolver(RulesCompiler compiler) { this.compiler=Objects.requireNonNull(compiler); }
    public Result resolve(String source,Function<Reference,String> lookup) {
        try { return resolve(source,Objects.requireNonNull(lookup),new LinkedHashSet<>(),new long[]{0}); }
        catch(Invalid error) { return new Result(null,null,List.of(error.diagnostic)); }
    }
    private Result resolve(String source,Function<Reference,String> lookup,Set<Reference> active,long[] size) {
        check(source!=null&&source.length()<=1_000_000,"SOURCE_LIMIT",null,"","A document of at most 1,000,000 characters is required");
        size[0]+=source.length();check(size[0]<=4_000_000&&active.size()<16,"RESOLUTION_LIMIT",null,"","Inheritance exceeds document/depth bounds");
        JsonNode root;
        try { root=JSON.readTree(source); }catch(JsonProcessingException invalid) { throw new Invalid("JSON_SYNTAX",null,"",invalid.getOriginalMessage()); }
        check(root!=null&&root.isObject(),"MODE_SHAPE",null,"","A definition or mode object is required");
        numbers(root);
        String id=root.path("id").asText("");var reference=new Reference(id,root.path("revision").asText(""));
        check(active.add(reference),"MODE_CYCLE",id,"/extends","Inheritance cycle detected");
        var layers=new ArrayList<Layer>();var origins=new TreeMap<String,Reference>();var changes=new ArrayList<Change>();ObjectNode resolved;
        if(!root.has("modeVersion")) { resolved=(ObjectNode)root.deepCopy();stamp(resolved,"",reference,origins); }
        else {
            shape(root,Set.of("modeVersion","id","revision","extends","overrides"),id,"");
            check(root.get("modeVersion").isIntegralNumber()&&root.get("modeVersion").bigIntegerValue().equals(java.math.BigInteger.ONE),"MODE_VERSION",id,"/modeVersion","Only mode version 1 is supported");
            check(root.get("id").isTextual()&&id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")&&root.get("revision").isTextual()&&reference.revision().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"),"MODE_ID",id,"","Invalid mode identity");
            var parent=root.get("extends");shape(parent,Set.of("id","revision","fingerprint"),id,"/extends");
            check(parent.get("id").isTextual()&&parent.get("revision").isTextual()&&parent.get("fingerprint").isTextual()&&parent.get("fingerprint").textValue().matches("[0-9a-f]{64}"),"BASE_REFERENCE",id,"/extends","An exact base identity and SHA-256 fingerprint are required");
            var baseRef=new Reference(parent.get("id").textValue(),parent.get("revision").textValue());
            check(!active.contains(baseRef),"MODE_CYCLE",id,"/extends","Inheritance cycle detected");
            String base=lookup.apply(baseRef);check(base!=null,"BASE_NOT_FOUND",id,"/extends","Pinned published base was not found");
            var result=resolve(base,lookup,active,size);if(!result.valid())return result;
            check(result.definition().id().equals(baseRef.id())&&result.definition().revision().equals(baseRef.revision()),"BASE_IDENTITY",id,"/extends","Lookup returned another definition revision");
            check(result.definition().fingerprint().equals(parent.get("fingerprint").textValue()),"BASE_FINGERPRINT",id,"/extends/fingerprint","Pinned base fingerprint differs from resolved base");
            try { resolved=(ObjectNode)JSON.readTree(result.definition().canonicalJson()); }catch(JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
            layers.addAll(result.resolution().layers());origins.putAll(result.resolution().origins());changes.addAll(result.resolution().changes());
            var overrides=root.get("overrides");check(overrides.isArray()&&overrides.size()<=256,"OVERRIDE_LIMIT",id,"/overrides","Provide at most 256 ordered overrides");
            for(int i=0;i<overrides.size();i++) {
                var patch=overrides.get(i);String at="/overrides/"+i,op=patch.path("op").asText("");
                check(Set.of("add","replace","remove").contains(op),"OVERRIDE_OPERATION",id,at+"/op","Use add, replace or remove");
                shape(patch,op.equals("remove")?Set.of("op","path"):Set.of("op","path","value"),id,at);
                check(patch.get("path").isTextual(),"OVERRIDE_PATH",id,at+"/path","JSON Pointer required");String pointer=patch.get("path").textValue();var segments=segments(pointer,id,at);
                check(!Set.of("id","revision","languageVersion").contains(segments.getFirst()),"OVERRIDE_IDENTITY",id,at,"Mode identity and language version cannot be patched");
                JsonNode container=resolved;
                for(int n=0;n<segments.size()-1;n++) { check(container.isObject()&&container.has(segments.get(n)),"OVERRIDE_PATH",id,at,"Parent object does not exist; arrays must be replaced whole");container=container.get(segments.get(n)); }
                check(container.isObject(),"OVERRIDE_PATH",id,at,"Override targets must be object members; arrays are atomic");String key=segments.getLast();
                check(op.equals("add")?!container.has(key):container.has(key),"OVERRIDE_TARGET",id,at,"add requires an absent member; replace/remove require an existing member");
                origins.keySet().removeIf(p->p.equals(pointer)||p.startsWith(pointer+"/"));
                if(op.equals("remove"))((ObjectNode)container).remove(key);
                else { ((ObjectNode)container).set(key,patch.get("value").deepCopy());stamp(patch.get("value"),pointer,reference,origins); }
                changes.add(new Change(reference,op,pointer));check(canonical(resolved).length()<=1_000_000,"SOURCE_LIMIT",id,at,"Expanded definition exceeds size bound");
            }
            resolved.put("id",id).put("revision",reference.revision());origins.put("/id",reference);origins.put("/revision",reference);
        }
        var compiled=compiler.compile(canonical(resolved));if(!compiled.valid())return new Result(null,null,compiled.diagnostics());
        layers.add(new Layer(reference,hash(canonical(root)),compiled.definition().fingerprint()));active.remove(reference);
        var leaves=new TreeMap<String,Reference>();stamp(resolved,"",reference,leaves);leaves.replaceAll((p,owner)->origins.getOrDefault(p,owner));
        var resolution=new Resolution(VERSION,layers,leaves,changes);
        try { check(JSON.writeValueAsString(resolution).length()<=1_000_000,"RESOLUTION_LIMIT",id,"","Provenance exceeds size bound"); }
        catch(JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        return new Result(compiled.definition(),resolution,List.of());
    }
    private static List<String> segments(String pointer,String id,String path) {
        check(pointer.startsWith("/")&&pointer.length()<=4096,"OVERRIDE_PATH",id,path,"Non-root JSON Pointer required");var segments=pointer.substring(1).split("/",-1);
        check(segments.length<=64,"OVERRIDE_PATH",id,path,"Pointer exceeds depth bound");var result=new ArrayList<String>();
        for(String segment:segments) { check(!segment.matches(".*~(?:[^01]|$).*"),"OVERRIDE_PATH",id,path,"Invalid JSON Pointer escape");result.add(segment.replace("~1","/").replace("~0","~")); }return result;
    }
    private static void stamp(JsonNode node,String path,Reference owner,Map<String,Reference> origins) {
        if(!node.isObject()&&!node.isArray()||node.isEmpty()) { origins.put(path,owner);return; }
        if(node.isArray())for(int i=0;i<node.size();i++)stamp(node.get(i),path+"/"+i,owner,origins);
        else node.fields().forEachRemaining(e->stamp(e.getValue(),path+"/"+e.getKey().replace("~","~0").replace("/","~1"),owner,origins));
    }
    private static void shape(JsonNode node,Set<String> fields,String id,String path) { var names=new HashSet<String>();if(node!=null)node.fieldNames().forEachRemaining(names::add);check(node!=null&&node.isObject()&&names.equals(fields),"MODE_SHAPE",id,path,"Unexpected or missing fields"); }
    private static void check(boolean valid,String code,String id,String path,String message) { if(!valid)throw new Invalid(code,id,path,message); }
    private static void numbers(JsonNode node) {
        if(node.isFloatingPointNumber()) { var value=node.decimalValue().stripTrailingZeros();check(value.precision()<=34&&Math.abs((long)value.scale())<=128,"NUMERIC_LIMIT",null,"","Decimal exceeds precision/scale bounds"); }
        if(node.isContainerNode())node.forEach(RulesResolver::numbers);
    }
    private static String canonical(JsonNode node) {
        if(node.isObject()) { var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);return "{"+keys.stream().map(k->JSON.getNodeFactory().textNode(k).toString()+":"+canonical(node.get(k))).collect(java.util.stream.Collectors.joining(","))+"}"; }
        if(node.isArray()) { var parts=new ArrayList<String>();node.forEach(n->parts.add(canonical(n)));return "["+String.join(",",parts)+"]"; }
        if(node.isFloatingPointNumber()) { var n=node.decimalValue().stripTrailingZeros();String text=n.toPlainString();return text.contains(".")?text:text+".0"; }return node.toString();
    }
    private static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
