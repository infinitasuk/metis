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
            "armada-squadron-counter-v1","armada-squadron-counter",
            "armada-ship-maneuvers-v1","armada-ship-maneuvers",
            "armada-ship-navigation-v1","armada-ship-navigation");
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
        String source=pack.path("captureCommands").asBoolean()?repository.commandCatalogueSource(version,pack.get("units").toString(),pack.get("pointsSource").textValue(),pack.get("maneuverSource").textValue())
                :pack.has("maneuverSource")?repository.maneuverCatalogueSource(version,pack.get("units").toString(),pack.get("pointsSource").textValue(),pack.get("maneuverSource").textValue())
                :pack.path("captureKeywords").asBoolean()?repository.keywordCatalogueSource(version,pack.get("units").toString(),pack.get("pointsSource").textValue())
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
        if(pack.has("maneuverSource")) { populateManeuvers(template,units,pack);populatePoints(template,units);if(pack.path("captureCommands").asBoolean())populateCommands(template,units);return; }
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
        if(pack.has("pointsSource"))populatePoints(template,units);
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
    private static void populatePoints(ObjectNode template,JsonNode units) {
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
    private static BigDecimal dimension(JsonNode geometry,String field,boolean zeroAllowed,BigDecimal maximum) {
        var value=geometry.path(field);check(value.isNumber(),422,"CATALOGUE_GEOMETRY_INVALID");var number=value.decimalValue();
        check((zeroAllowed?number.signum()>=0:number.signum()>0)&&number.compareTo(maximum)<=0,422,"CATALOGUE_GEOMETRY_INVALID");return number;
    }
    /** Data-only adapter for the explicitly scoped, rectangular-base maneuver recipe. */
    private static void populateManeuvers(ObjectNode template,JsonNode units,JsonNode pack) {
        var fields=new TreeMap<String,ArrayNode>();for(String field:List.of("names","catalogueIds","factions","hulls","maxSpeeds","charts","maneuverSources"))fields.put(field,JsonNodeFactory.instance.arrayNode());
        JsonNode geometry=null;
        for(int i=0;i<units.size();i++) {
            var unit=units.get(i);check(unit.path("displayName").asText().equals(pack.get("units").get(i).textValue())&&unit.path("classification").asText().equals(pack.get("classification").textValue()),422,"CATALOGUE_PROFILE_MISMATCH");
            check(unit.path("code").isTextual()&&!unit.get("code").textValue().isBlank()&&unit.path("factionCode").isTextual(),422,"CATALOGUE_PROFILE_MISSING");
            var hull=unit.path("maximumHullPoints");check(hull.isIntegralNumber()&&hull.canConvertToInt()&&hull.intValue()>0&&hull.intValue()<=100,422,"CATALOGUE_HULL_INVALID");
            var speed=unit.path("maximumMovementDistance");check(speed.isNumber()&&speed.decimalValue().stripTrailingZeros().scale()<=0&&speed.decimalValue().compareTo(BigDecimal.ONE)>=0&&speed.decimalValue().compareTo(BigDecimal.valueOf(8))<=0,422,"CATALOGUE_SPEED_INVALID");
            var chart=unit.path("maneuverChart");check(chart.isArray()&&chart.size()==speed.intValue()+1,422,"CATALOGUE_MANEUVER_CHART");
            for(int row=0;row<chart.size();row++) {
                check(chart.get(row).isArray()&&chart.get(row).size()==row,422,"CATALOGUE_MANEUVER_CHART");
                for(var yaw:chart.get(row))check(yaw.isIntegralNumber()&&yaw.canConvertToInt()&&yaw.intValue()>=0&&yaw.intValue()<=8,422,"CATALOGUE_MANEUVER_CHART");
            }
            var source=unit.path("maneuverSource");check(source.isObject(),422,"CATALOGUE_MANEUVER_SOURCE");
            for(String field:List.of("repository","file","url","licence"))check(source.path(field).isTextual()&&!source.get(field).textValue().isBlank(),422,"CATALOGUE_MANEUVER_SOURCE");
            check(source.path("commit").asText().matches("[0-9a-f]{40}")&&source.path("sha256").asText().matches("[0-9a-f]{64}")&&source.path("calibration").isArray()&&!source.get("calibration").isEmpty(),422,"CATALOGUE_MANEUVER_SOURCE");
            for(var reference:source.get("calibration"))check(reference.path("file").isTextual()&&!reference.get("file").textValue().isBlank()&&reference.path("sha256").asText().matches("[0-9a-f]{64}"),422,"CATALOGUE_MANEUVER_SOURCE");
            var current=unit.path("maneuverGeometry");check(current.isObject()&&current.path("unit").asText().matches("[a-z][a-zA-Z0-9_-]{0,63}"),422,"CATALOGUE_GEOMETRY_INVALID");
            for(String field:List.of("width","height","segment"))dimension(current,field,false,BigDecimal.valueOf(1e9));
            dimension(current,"angle",false,BigDecimal.valueOf(90));dimension(current,"toolWidth",true,BigDecimal.valueOf(1e9));
            var anchor=current.path("anchor");check(anchor.isArray()&&anchor.size()==2&&anchor.get(0).isNumber()&&anchor.get(1).isNumber()
                    &&anchor.get(0).decimalValue().abs().compareTo(BigDecimal.valueOf(1e9))<=0&&anchor.get(1).decimalValue().signum()>=0&&anchor.get(1).decimalValue().compareTo(BigDecimal.valueOf(1e9))<=0,422,"CATALOGUE_GEOMETRY_INVALID");
            check(geometry==null||geometry.equals(current),422,"CATALOGUE_FOOTPRINT_MISMATCH");geometry=current;
            fields.get("names").add(unit.get("displayName"));fields.get("catalogueIds").add(unit.get("code"));fields.get("factions").add(unit.get("factionCode"));fields.get("hulls").add(hull);
            fields.get("maxSpeeds").add(speed.intValue());fields.get("charts").add(chart);fields.get("maneuverSources").add(source.toString());
        }
        check(geometry!=null,422,"CATALOGUE_PROFILE_MISSING");fields.forEach((field,values)->((ObjectNode)template.at("/state/"+field)).set("initial",values));
        for(var binding:Map.of("segment","segment","yawAngle","angle","anchor","anchor","toolWidth","toolWidth").entrySet())((ObjectNode)template.at("/state/"+binding.getKey())).set("initial",geometry.get(binding.getValue()).deepCopy());
        ((ObjectNode)template.at("/space/boards/table")).set("unit",geometry.get("unit"));
        var body=(ObjectNode)template.at("/space/bodies/ship");body.set("width",geometry.get("width"));body.set("height",geometry.get("height"));
    }
    private RulesRepository.Catalogue entry(long id) {
        return repository.catalogue().stream().filter(e->e.id()==id).findFirst().orElseThrow(()->new RulesService.Failure(404,"CATALOGUE_ENTRY_NOT_FOUND"));
    }
    private static void populateCommands(ObjectNode template,JsonNode units) {
        var ratings=JsonNodeFactory.instance.arrayNode();var sources=ratings.deepCopy();int maximum=template.at("/state/commandSlots/initial").size();
        for(var unit:units) {
            var value=unit.path("commandValue");check(value.isIntegralNumber()&&value.canConvertToInt()&&value.intValue()>0&&value.intValue()<=maximum,422,"CATALOGUE_COMMAND_VALUE");
            var source=unit.path("commandSource");var characteristic=source.path("characteristic");var provenance=unit.path("pointsSource");
            check(source.isObject()&&source.path("rawValue").isTextual()&&source.path("provenance").equals(provenance)
                    &&characteristic.path("characteristic").asText().equals("Command")&&characteristic.path("profile").asText().equals(unit.path("displayName").asText())
                    &&characteristic.path("catalogue").equals(provenance.get("file"))&&characteristic.path("revision").equals(provenance.get("revision")),422,"CATALOGUE_COMMAND_SOURCE");
            try { check(new BigDecimal(source.get("rawValue").textValue()).compareTo(BigDecimal.valueOf(value.intValue()))==0,422,"CATALOGUE_COMMAND_SOURCE"); }
            catch(NumberFormatException error) { throw new RulesService.Failure(422,"CATALOGUE_COMMAND_SOURCE"); }
            check(unit.get("maneuverChart").size()<=template.at("/state/jointSlots/initial").size()+1,422,"CATALOGUE_SCOPE_UNSUPPORTED");
            for(var row:unit.get("maneuverChart"))for(var yaw:row)check(yaw.intValue()<=template.at("/state/maximumYaw/initial").intValue(),422,"CATALOGUE_SCOPE_UNSUPPORTED");
            ratings.add(value);sources.add(source.toString());
        }
        ((ObjectNode)template.at("/state/commandRatings")).set("initial",ratings);((ObjectNode)template.at("/state/commandSources")).set("initial",sources);
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
