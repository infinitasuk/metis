package com.metis.engine.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.RulesCompiler;
import com.metis.engine.core.rules.RulesResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** One-way catalogue data import. Executable policy is authored in the packaged JSON recipe. */
@Service
public class RulesCatalogueService {
    private static final Map<String,String> PACKS=Map.of(
            "armada-squadron-training-v1","armada-squadron-training",
            "armada-squadron-training-m8","armada-squadron-training-m8",
            "armada-squadron-points-v1","armada-squadron-points",
            "armada-squadron-rosters-v1","armada-squadron-rosters",
            "armada-squadron-counter-v1","armada-squadron-counter");
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
    private ObjectNode template(String file,JsonNode pack,Set<String> parents) {
        check(parents.size()<16&&parents.add(file),422,"CATALOGUE_TEMPLATE_CYCLE");
        ObjectNode base;
        if(pack.has("extendsPack")) {
            String parent=pack.get("extendsPack").textValue();base=template(parent,resource(parent+".pack"),parents);
        } else base=resource(pack.path("template").asText(file));
        return (ObjectNode)rules.parse(compose(base,pack));
    }
    @Transactional public List<Entry> importCatalogue(JsonNode request,MetisUser user) {
        check(user.admin(),403,"ADMIN_REQUIRED");shape(request,Set.of("gameVersionId","pack"));
        check(request.get("gameVersionId").isIntegralNumber()&&request.get("gameVersionId").canConvertToLong()&&request.get("pack").isTextual(),400,"REQUEST_FIELD_INVALID");
        long version=request.get("gameVersionId").longValue();String packId=request.get("pack").textValue(),file=PACKS.get(packId);check(file!=null,422,"CATALOGUE_PACK_UNKNOWN");
        check(repository.lockCatalogue(version),409,"CATALOGUE_NOT_PUBLISHED");var pack=resource(file+".pack");
        var template=template(file,pack,new HashSet<>());
        String source=pack.path("captureKeywords").asBoolean()?repository.keywordCatalogueSource(version,pack.get("units").toString(),pack.get("pointsSource").textValue())
                :pack.has("pointsSource")?repository.pricedCatalogueSource(version,pack.get("units").toString(),pack.get("pointsSource").textValue())
                :repository.catalogueSource(version,pack.get("units").toString());
        check(source!=null,409,"CATALOGUE_NOT_PUBLISHED");var snapshot=(ObjectNode)rules.parse(source);
        check(snapshot.path("gameName").asText().equals(pack.get("game").textValue()),422,"CATALOGUE_GAME_MISMATCH");
        if(pack.has("rulesSource"))snapshot.set("rulesSource",pack.get("rulesSource").deepCopy());
        snapshot.put("importerVersion","rules-catalogue-1").put("recipeHash",hash(pack.toString())).put("templateHash",hash(template.toString()));String capture=snapshot.toString(),fingerprint=hash(capture);
        var existing=repository.catalogue().stream().filter(e->e.sourceVersion()==version&&e.pack().equals(packId)&&e.fingerprint().equals(fingerprint)).toList();
        if(!existing.isEmpty()) { check(existing.size()==1+pack.get("modes").size(),409,"CATALOGUE_IMPORT_INCOMPLETE");return existing.stream().map(this::view).toList(); }
        populate(template,snapshot,pack);template.put("revision",fingerprint);((ObjectNode)template.at("/state/catalogueFingerprint")).put("initial",fingerprint);
        if(pack.has("pointsSource"))new CatalogueRosters(template.toString()).validateDefaults();
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
    /** Reuse the mode resolver for packaged recipe overrides as well as published modes. */
    static String compose(ObjectNode base,JsonNode pack) {
        if(!pack.has("overrides"))return base.toString();
        var compiled=new RulesCompiler().compile(base.toString());check(compiled.valid(),422,"CATALOGUE_TEMPLATE_INVALID");
        var mode=JsonNodeFactory.instance.objectNode().put("modeVersion",1).put("id",pack.get("definition").textValue()).put("revision","template");
        mode.putObject("extends").put("id",compiled.definition().id()).put("revision",compiled.definition().revision()).put("fingerprint",compiled.definition().fingerprint());
        mode.set("overrides",pack.get("overrides"));
        var result=new RulesResolver().resolve(mode.toString(),ref->base.toString());
        if(!result.valid())throw new RulesService.Failure(422,"CATALOGUE_TEMPLATE_INVALID",result.diagnostics());
        return result.definition().canonicalJson();
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
        if(pack.has("pointsSource")) {
            var points=JsonNodeFactory.instance.arrayNode();var sources=points.deepCopy();var unique=points.deepCopy();
            for(var unit:units) {
                var cost=unit.path("pointsCost");var provenance=unit.path("pointsSource");
                check(cost.isIntegralNumber()&&cost.canConvertToLong()&&cost.longValue()>0,422,"CATALOGUE_POINTS_MISSING");
                check(provenance.isObject()&&unit.path("uniqueKey").isTextual(),422,"CATALOGUE_POINTS_PROVENANCE");
                for(String key:List.of("repository","file","revision","entryId","url","licence","rawPoints"))
                    check(provenance.path(key).isTextual()&&!provenance.get(key).textValue().isBlank(),422,"CATALOGUE_POINTS_PROVENANCE");
                check(provenance.path("commit").asText().matches("[0-9a-f]{40}")&&provenance.path("sha256").asText().matches("[0-9a-f]{64}"),422,"CATALOGUE_POINTS_PROVENANCE");
                try { check(new BigDecimal(provenance.get("rawPoints").textValue()).compareTo(BigDecimal.valueOf(cost.longValue()))==0,422,"CATALOGUE_POINTS_PROVENANCE"); }
                catch(NumberFormatException error) { throw new RulesService.Failure(422,"CATALOGUE_POINTS_PROVENANCE"); }
                points.add(cost);sources.add(provenance.toString());unique.add(unit.get("uniqueKey"));
            }
            ((ObjectNode)template.at("/state/points")).set("initial",points);
            ((ObjectNode)template.at("/state/pointSources")).set("initial",sources);
            ((ObjectNode)template.at("/state/uniqueKeys")).set("initial",unique);
        }
        if(pack.path("captureKeywords").asBoolean()) {
            var fields=new TreeMap<String,ArrayNode>();pack.get("keywordDefaults").fieldNames().forEachRemaining(field->fields.put(field,JsonNodeFactory.instance.arrayNode()));
            var keywords=JsonNodeFactory.instance.arrayNode();var sources=JsonNodeFactory.instance.arrayNode();
            for(var unit:units) {
                check(unit.path("keywords").isArray()&&unit.path("keywordSource").isObject()&&unit.path("abilitySource").isObject(),422,"CATALOGUE_KEYWORDS_MISSING");
                check(unit.has("squadronAbility")&&unit.get("squadronAbility").isNull(),422,"CATALOGUE_SCOPE_UNSUPPORTED");
                var values=new TreeMap<String,JsonNode>();fields.keySet().forEach(field->values.put(field,pack.get("keywordDefaults").get(field).deepCopy()));
                var seen=new HashSet<String>();
                for(var keyword:unit.get("keywords")) {
                    check(keyword.isTextual()&&seen.add(keyword.textValue()),422,"CATALOGUE_KEYWORDS_INVALID");
                    var binding=pack.path("keywordBindings").path(keyword.textValue());check(!binding.isMissingNode(),422,"CATALOGUE_KEYWORD_UNSUPPORTED");
                    if(binding.has("inapplicable"))continue;
                    String field=binding.path("field").asText();check(fields.containsKey(field)&&binding.has("value"),422,"CATALOGUE_TEMPLATE_INVALID");
                    values.put(field,binding.get("value").deepCopy());
                }
                fields.forEach((field,array)->array.add(values.get(field)));keywords.add(unit.get("keywords"));sources.add(unit.get("keywordSource").toString());
            }
            fields.forEach((field,array)->((ObjectNode)template.at("/state/"+field)).set("initial",array));
            ((ObjectNode)template.at("/state/keywordProfiles")).set("initial",keywords);
            ((ObjectNode)template.at("/state/keywordSources")).set("initial",sources);
        }
    }
    private RulesRepository.Catalogue entry(long id) {
        return repository.catalogue().stream().filter(e->e.id()==id).findFirst().orElseThrow(()->new RulesService.Failure(404,"CATALOGUE_ENTRY_NOT_FOUND"));
    }
    private CatalogueRosters rosters(RulesRepository.Catalogue entry) {
        var definition=repository.definition(entry.definitionId(),false).orElseThrow(()->new RulesService.Failure(404,"DEFINITION_NOT_FOUND"));
        return new CatalogueRosters(definition.executable());
    }
    public CatalogueRosters.View roster(long entryId) { return rosters(entry(entryId)).view(); }
    public CatalogueRosters.Validation validateRoster(long entryId,JsonNode request) {
        return rosters(entry(entryId)).validateRequest(request);
    }
    @Transactional public RulesService.SessionView createSession(long entryId,JsonNode request,MetisUser user) {
        check(request!=null&&request.isObject(),400,"REQUEST_FIELDS_INVALID");
        var fields=new HashSet<>(Set.of("visibility","seats","viewers"));
        if(request.has("cpuCommandLimit"))fields.add("cpuCommandLimit");if(request.has("rosters"))fields.add("rosters");
        shape(request,fields);var entry=entry(entryId);
        var roster=rosters(entry);if(roster.view().available()) {
            check(request.get("seats").isArray()&&request.get("seats").size()==roster.view().seats().size(),422,"ROSTER_SEAT_COUNT");
            roster.validateDefaults();
        }
        var body=(ObjectNode)request.deepCopy();body.put("definitionId",entry.definitionId());return rules.createSession(body,user);
    }
    private static void shape(JsonNode node,Set<String> keys) { var found=new HashSet<String>();if(node!=null)node.fieldNames().forEachRemaining(found::add);check(node!=null&&node.isObject()&&found.equals(keys),400,"REQUEST_FIELDS_INVALID"); }
    private static String hash(String text) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);} }
}
