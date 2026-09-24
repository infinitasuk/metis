package com.metis.engine.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One-way catalogue data import. Executable policy is authored in the packaged JSON recipe. */
@Service
public class RulesCatalogueService {
    private static final Map<String,String> PACKS=Map.of("armada-squadron-training-v1","armada-squadron-training");
    private final RulesRepository repository;
    private final RulesService rules;
    public RulesCatalogueService(RulesRepository repository,RulesService rules) { this.repository=repository;this.rules=rules; }
    public record Entry(long id,long gameId,long sourceVersionId,String pack,String mode,String name,long definitionId,String sourceFingerprint,JsonNode scope) { }
    private static void check(boolean condition,int status,String code) { if(!condition)throw new RulesService.Failure(status,code); }
    private Entry view(RulesRepository.Catalogue entry) { return new Entry(entry.id(),entry.gameId(),entry.sourceVersion(),entry.pack(),entry.mode(),entry.name(),entry.definitionId(),entry.fingerprint(),rules.parse(entry.scope())); }
    public List<Entry> catalogue() { return repository.catalogue().stream().map(this::view).toList(); }
    private ObjectNode resource(String name) {
        try(var stream=RulesCatalogueService.class.getResourceAsStream("/rules/catalogue/"+name+".json")) {
            if(stream==null)throw new IllegalStateException("Missing packaged catalogue recipe");return (ObjectNode)rules.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }catch(java.io.IOException error) { throw new IllegalStateException("Cannot read catalogue recipe",error); }
    }
    @Transactional public List<Entry> importCatalogue(JsonNode request,MetisUser user) {
        check(user.admin(),403,"ADMIN_REQUIRED");shape(request,Set.of("gameVersionId","pack"));
        check(request.get("gameVersionId").isIntegralNumber()&&request.get("gameVersionId").canConvertToLong()&&request.get("pack").isTextual(),400,"REQUEST_FIELD_INVALID");
        long version=request.get("gameVersionId").longValue();String packId=request.get("pack").textValue(),file=PACKS.get(packId);check(file!=null,422,"CATALOGUE_PACK_UNKNOWN");
        check(repository.lockCatalogue(version),409,"CATALOGUE_NOT_PUBLISHED");var pack=resource(file+".pack");var template=resource(file);
        String source=repository.catalogueSource(version,pack.get("units").toString());check(source!=null,409,"CATALOGUE_NOT_PUBLISHED");var snapshot=(ObjectNode)rules.parse(source);
        check(snapshot.path("gameName").asText().equals(pack.get("game").textValue()),422,"CATALOGUE_GAME_MISMATCH");
        snapshot.put("importerVersion","rules-catalogue-1").put("recipeHash",hash(pack.toString())).put("templateHash",hash(template.toString()));String capture=snapshot.toString(),fingerprint=hash(capture);
        var existing=repository.catalogue().stream().filter(e->e.sourceVersion()==version&&e.pack().equals(packId)&&e.fingerprint().equals(fingerprint)).toList();
        if(!existing.isEmpty()) { check(existing.size()==1+pack.get("modes").size(),409,"CATALOGUE_IMPORT_INCOMPLETE");return existing.stream().map(this::view).toList(); }
        populate(template,snapshot,pack);template.put("revision",fingerprint);((ObjectNode)template.at("/state/catalogueFingerprint")).put("initial",fingerprint);
        var draft=rules.createDefinition(template,user);var base=rules.publish(draft.id(),draft.version(),user);
        repository.registerCatalogue(version,packId,"standard",pack.get("title").textValue()+" — Standard",base.id(),fingerprint,capture,pack.get("scope").toString());
        for(var variant:pack.get("modes")) {
            var mode=JsonNodeFactory.instance.objectNode().put("modeVersion",1).put("id",base.key()+"-"+variant.get("code").textValue()).put("revision",fingerprint);
            mode.putObject("extends").put("id",base.key()).put("revision",base.revision()).put("fingerprint",base.fingerprint());mode.set("overrides",variant.get("overrides").deepCopy());
            var child=rules.createDefinition(mode,user);var published=rules.publish(child.id(),child.version(),user);
            repository.registerCatalogue(version,packId,variant.get("code").textValue(),pack.get("title").textValue()+" — "+variant.get("name").textValue(),published.id(),fingerprint,capture,pack.get("scope").toString());
        }
        return repository.catalogue().stream().filter(e->e.sourceVersion()==version&&e.pack().equals(packId)&&e.fingerprint().equals(fingerprint)).map(this::view).toList();
    }
    /** Translate only measured catalogue data; all timing, targeting, symbols and endings stay in JSON. */
    static void populate(ObjectNode template,JsonNode snapshot,JsonNode pack) {
        var units=snapshot.path("units");check(units.isArray()&&units.size()==pack.get("units").size(),422,"CATALOGUE_PROFILE_MISSING");
        var names=JsonNodeFactory.instance.arrayNode();var ids=names.deepCopy();var factions=names.deepCopy();var hulls=names.deepCopy();var speeds=names.deepCopy();var pools=names.deepCopy();BigDecimal radius=null;
        for(int i=0;i<units.size();i++) {
            var unit=units.get(i);check(unit.path("displayName").asText().equals(pack.get("units").get(i).textValue())&&unit.path("classification").asText().equals(pack.get("classification").textValue()),422,"CATALOGUE_PROFILE_MISMATCH");
            check(unit.path("code").isTextual()&&!unit.get("code").textValue().isBlank()&&unit.path("factionCode").isTextual(),422,"CATALOGUE_PROFILE_MISSING");
            check(unit.path("maximumHullPoints").isIntegralNumber()&&unit.get("maximumHullPoints").canConvertToInt()&&unit.get("maximumHullPoints").intValue()>0&&unit.get("maximumHullPoints").intValue()<=100,422,"CATALOGUE_HULL_INVALID");
            check(unit.path("maximumMovementDistance").isNumber()&&unit.path("baseRadius").isNumber()&&unit.path("defenseTokenCount").isIntegralNumber(),422,"CATALOGUE_PROFILE_MISSING");
            var speed=unit.get("maximumMovementDistance").decimalValue();var base=unit.get("baseRadius").decimalValue();
            check(speed.signum()>0&&speed.compareTo(BigDecimal.TEN)<=0&&base.signum()>0&&base.compareTo(BigDecimal.valueOf(100))<=0,422,"CATALOGUE_GEOMETRY_INVALID");
            check(unit.get("defenseTokenCount").bigIntegerValue().signum()==0,422,"CATALOGUE_SCOPE_UNSUPPORTED");check(radius==null||radius.compareTo(base)==0,422,"CATALOGUE_FOOTPRINT_MISMATCH");radius=base;
            var pool=JsonNodeFactory.instance.arrayNode();check(unit.path("antiSquadronDice").isArray(),422,"CATALOGUE_POOL_INVALID");
            for(var die:unit.get("antiSquadronDice")) {
                check(die.path("color").isTextual()&&die.path("count").isIntegralNumber()&&die.get("count").canConvertToInt(),422,"CATALOGUE_POOL_INVALID");
                String color=die.get("color").textValue().toLowerCase(Locale.ROOT);int count=die.get("count").intValue();check(template.get("dice").has(color)&&count>0&&count<=64&&pool.size()+count<=64,422,"CATALOGUE_POOL_INVALID");for(int n=0;n<count;n++)pool.add(color);
            }
            check(!pool.isEmpty(),422,"CATALOGUE_POOL_INVALID");names.add(unit.get("displayName"));ids.add(unit.get("code"));factions.add(unit.get("factionCode"));hulls.add(unit.get("maximumHullPoints"));speeds.add(speed.multiply(BigDecimal.valueOf(100)));pools.add(pool);
        }
        for(var entry:Map.of("names",names,"catalogueIds",ids,"factions",factions,"hulls",hulls,"speeds",speeds,"pools",pools).entrySet())((ObjectNode)template.at("/state/"+entry.getKey())).set("initial",entry.getValue());
        ((ObjectNode)template.at("/space/bodies/squadron")).put("radius",radius);
    }
    @Transactional public RulesService.SessionView createSession(long entryId,JsonNode request,MetisUser user) {
        shape(request,request.has("cpuCommandLimit")?Set.of("visibility","seats","viewers","cpuCommandLimit"):Set.of("visibility","seats","viewers"));var entry=repository.catalogue().stream().filter(e->e.id()==entryId).findFirst().orElseThrow(()->new RulesService.Failure(404,"CATALOGUE_ENTRY_NOT_FOUND"));
        var body=(ObjectNode)request.deepCopy();body.put("definitionId",entry.definitionId());return rules.createSession(body,user);
    }
    private static void shape(JsonNode node,Set<String> keys) { var found=new HashSet<String>();if(node!=null)node.fieldNames().forEachRemaining(found::add);check(node!=null&&node.isObject()&&found.equals(keys),400,"REQUEST_FIELDS_INVALID"); }
    private static String hash(String text) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);} }
}
