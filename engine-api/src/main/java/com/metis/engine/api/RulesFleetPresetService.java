package com.metis.engine.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Personal, revision-pinned selections. Loading revalidates a draft, never edits a preset or a session. */
@Service
public class RulesFleetPresetService {
    public record Selection(String policy,List<String> units) { public Selection { units=List.copyOf(units); } }
    public record Snapshot(String definition,String revision,String fingerprint,long points,List<CatalogueRosters.Unit> units) { public Snapshot { units=List.copyOf(units); } }
    public record View(long id,String name,long version,long definitionId,int seat,Selection selection,Snapshot snapshot) { }
    public record Loaded(boolean valid,String code,boolean revisionChanged,long savedPoints,Long points,Long budget,String fingerprint,Selection selection) { }
    private record Source(RulesRepository.Definition definition,CatalogueRosters rosters) { }
    private final RulesRepository definitions;
    private final RulesFleetPresetRepository presets;
    private final ObjectMapper json;
    public RulesFleetPresetService(RulesRepository definitions,RulesFleetPresetRepository presets,ObjectMapper json) { this.definitions=definitions;this.presets=presets;this.json=json; }

    private static void check(boolean condition,int status,String code) { if(!condition)throw new RulesService.Failure(status,code); }
    private static void shape(JsonNode node,Set<String> keys) {
        var fields=new HashSet<String>();if(node!=null)node.fieldNames().forEachRemaining(fields::add);
        check(node!=null&&node.isObject()&&fields.equals(keys),400,"REQUEST_FIELDS_INVALID");
    }
    private static long number(JsonNode node,String field) {
        check(node.path(field).isIntegralNumber()&&node.get(field).canConvertToLong(),400,"REQUEST_FIELD_INVALID");return node.get(field).longValue();
    }
    private static int seat(JsonNode node) {
        long seat=number(node,"seat");check(seat>=0&&seat<=Integer.MAX_VALUE,400,"ROSTER_SEAT_INVALID");return (int)seat;
    }
    private static String name(JsonNode node) {
        check(node.path("name").isTextual(),400,"PRESET_NAME_INVALID");String name=node.get("name").textValue().strip();
        check(!name.isBlank()&&name.length()<=100,400,"PRESET_NAME_INVALID");return name;
    }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(JsonProcessingException error){throw new IllegalStateException(error);} }
    private <T> T read(String value,Class<T> type) { try{return json.readValue(value,type);}catch(JsonProcessingException error){throw new RulesService.Failure(409,"PRESET_SNAPSHOT_INVALID");} }
    private Source source(long id) {
        var definition=definitions.definition(id,false).orElseThrow(()->new RulesService.Failure(404,"DEFINITION_NOT_FOUND"));
        check(definition.lifecycle().equals("published"),404,"DEFINITION_NOT_FOUND");var roster=new CatalogueRosters(definition.executable());
        check(roster.view().fingerprint().equals(definition.fingerprint()),409,"SNAPSHOT_INCOMPATIBLE");
        check(roster.view().selectable(),422,"PRESET_DEFINITION_UNSUPPORTED");return new Source(definition,roster);
    }
    private RulesFleetPresetRepository.Preset owned(long id,MetisUser user) { return presets.find(user.userId(),id).orElseThrow(()->new RulesService.Failure(404,"PRESET_NOT_FOUND")); }
    private View view(RulesFleetPresetRepository.Preset row) {
        return new View(row.id(),row.name(),row.version(),row.definitionId(),row.seat(),new Selection(row.policy(),Arrays.asList(read(row.units(),String[].class))),read(row.snapshot(),Snapshot.class));
    }
    public List<View> list(MetisUser user) { return presets.list(user.userId()).stream().map(this::view).toList(); }
    public View get(long id,MetisUser user) { return view(owned(id,user)); }

    @Transactional public View create(JsonNode request,MetisUser user) {
        shape(request,Set.of("name","definitionId","seat","policy","units"));String name=name(request);
        var source=source(number(request,"definitionId"));int seat=seat(request);
        check(request.get("policy").isTextual(),400,"ROSTER_INPUT_INVALID");String policy=request.get("policy").textValue();
        var validation=source.rosters().validate(seat,policy,request.get("units"));check(validation.valid(),422,validation.code());
        var units=new ArrayList<CatalogueRosters.Unit>();request.get("units").forEach(code->units.add(source.rosters().view().units().stream().filter(unit->unit.code().equals(code.textValue())).findFirst().orElseThrow()));
        var snapshot=new Snapshot(source.definition().key(),source.definition().revision(),source.definition().fingerprint(),validation.points(),units);
        try {
            long id=presets.create(user.userId(),source.definition().id(),seat,name,policy,request.get("units").toString(),write(snapshot));
            return get(id,user);
        } catch(DataAccessException error) { throw storage(error); }
    }
    @Transactional public View rename(long id,JsonNode request,MetisUser user) {
        owned(id,user);shape(request,Set.of("name","expectedVersion"));long expected=number(request,"expectedVersion");check(expected>=0,400,"PRESET_VERSION_INVALID");
        try { presets.rename(user.userId(),id,expected,name(request));return get(id,user); }
        catch(DataAccessException error) { throw storage(error); }
    }
    @Transactional public void delete(long id,long expected,MetisUser user) {
        owned(id,user);check(expected>=0,400,"PRESET_VERSION_INVALID");
        try { presets.delete(user.userId(),id,expected); }
        catch(DataAccessException error) { throw storage(error); }
    }
    public Loaded load(long id,JsonNode request,MetisUser user) {
        var saved=view(owned(id,user));shape(request,Set.of("definitionId","seat"));var target=source(number(request,"definitionId"));int seat=seat(request);
        check(seat<target.rosters().view().seats().size(),400,"ROSTER_SEAT_INVALID");
        boolean changed=!saved.snapshot().fingerprint().equals(target.definition().fingerprint());
        var selected=new ArrayList<String>();
        for(var unit:saved.snapshot().units()) {
            var matches=target.rosters().view().units().stream().filter(candidate->changed?sameOrigin(unit,candidate):unit.code().equals(candidate.code())).toList();
            if(matches.size()!=1)return new Loaded(false,matches.isEmpty()?"PRESET_UNIT_UNAVAILABLE":"PRESET_UNIT_AMBIGUOUS",changed,saved.snapshot().points(),null,null,target.definition().fingerprint(),null);
            selected.add(matches.getFirst().code());
        }
        var selection=new Selection(saved.selection().policy(),selected);CatalogueRosters.Validation validation;
        try { validation=target.rosters().validate(seat,selection.policy(),json.valueToTree(selection.units())); }
        catch(RulesService.Failure error) {
            if(!error.code().equals("ROSTER_POLICY"))throw error;
            return new Loaded(false,error.code(),changed,saved.snapshot().points(),null,null,target.definition().fingerprint(),null);
        }
        return new Loaded(validation.valid(),validation.code(),changed,saved.snapshot().points(),validation.points(),validation.budget(),validation.fingerprint(),validation.valid()?selection:null);
    }
    private boolean sameOrigin(CatalogueRosters.Unit first,CatalogueRosters.Unit second) {
        try {
            JsonNode a=json.readTree(first.provenance()),b=json.readTree(second.provenance());
            if(a==null||b==null||!a.isObject()||!b.isObject())return false;
            for(String key:List.of("repository","file","entryId"))if(!a.path(key).isTextual()||a.get(key).textValue().isBlank()||!a.get(key).equals(b.path(key)))return false;
            return true;
        } catch(JsonProcessingException error) { return false; }
    }
    private RuntimeException storage(DataAccessException error) {
        var cause=error.getMostSpecificCause();String code=cause instanceof java.sql.SQLException sql?sql.getSQLState():"";
        return switch(code) {
            case "40001" -> new RulesService.Failure(409,"STALE_PRESET");
            case "23505" -> new RulesService.Failure(409,"PRESET_NAME_EXISTS");
            case "P0002" -> new RulesService.Failure(404,"PRESET_NOT_FOUND");
            default -> error;
        };
    }
}
