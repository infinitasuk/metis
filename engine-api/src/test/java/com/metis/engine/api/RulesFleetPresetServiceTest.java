package com.metis.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.RulesCompiler;
import com.metis.engine.core.rules.runtime.RulesRuntime;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.*;
import static com.metis.engine.api.RulesCatalogueTest.JSON;
import static org.junit.jupiter.api.Assertions.*;

class RulesFleetPresetServiceTest {
    private final MetisUser alice=new MetisUser(1,"alice","Alice","user"),bob=new MetisUser(2,"bob","Bob","user"),admin=new MetisUser(5,"admin","Admin","admin");
    private final Definitions definitions=new Definitions();
    private final Presets presets=new Presets();
    private final RulesFleetPresetService service=new RulesFleetPresetService(definitions,presets,JSON);
    private final ObjectNode original;
    RulesFleetPresetServiceTest() throws Exception { original=SelectableRostersTest.template();definitions.add(1,original); }
    private ObjectNode saveRequest(String name) throws Exception { return (ObjectNode)JSON.readTree("{\"name\":\""+name+"\",\"definitionId\":1,\"seat\":0,\"policy\":\"imperial\",\"units\":[\"123\",\"123\",\"123\"]}"); }
    private JsonNode target(long id) { return JSON.createObjectNode().put("definitionId",id).put("seat",0); }
    private void code(String code,Runnable call) { assertEquals(code,assertThrows(RulesService.Failure.class,call::run).code()); }

    @Test void saveIsValidatedOwnerScopedAndPinnedToItsSourceDefinition() throws Exception {
        var saved=service.create(saveRequest("  Fighters  "),alice);
        assertEquals("Fighters",saved.name());assertEquals(24,saved.snapshot().points());assertEquals(3,saved.snapshot().units().size());
        assertEquals(List.of("123","123","123"),saved.selection().units());assertEquals(definitions.rows.get(1L).fingerprint(),saved.snapshot().fingerprint());
        assertEquals(List.of(saved),service.list(alice));assertTrue(service.list(bob).isEmpty());assertTrue(service.list(admin).isEmpty());
        for(var user:List.of(bob,admin)) {
            code("PRESET_NOT_FOUND",()->service.get(saved.id(),user));code("PRESET_NOT_FOUND",()->service.load(saved.id(),target(1),user));
            code("PRESET_NOT_FOUND",()->service.delete(saved.id(),0,user));
            code("PRESET_NOT_FOUND",()->service.rename(saved.id(),JSON.createObjectNode().put("name","Stolen").put("expectedVersion",0),user));
        }
        var loaded=service.load(saved.id(),target(1),alice);assertTrue(loaded.valid());assertFalse(loaded.revisionChanged());assertEquals(24,loaded.points());
        assertEquals(saved.selection(),loaded.selection());assertEquals(saved,service.get(saved.id(),alice));
    }
    @Test void reuseRemapsStableSourceIdentitiesAndRevalidatesChangedCostsWithoutEditingThePreset() throws Exception {
        var saved=service.create(saveRequest("Fighters"),alice);
        var newer=original.deepCopy();newer.put("revision","new-costs");((com.fasterxml.jackson.databind.node.ArrayNode)newer.at("/state/catalogueIds/initial")).set(0,JSON.getNodeFactory().textNode("new-tie-id"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)newer.at("/state/points/initial")).set(0,JSON.getNodeFactory().numberNode(9));definitions.add(2,newer);
        var invalid=service.load(saved.id(),target(2),alice);assertFalse(invalid.valid());assertEquals("ROSTER_BUDGET",invalid.code());assertEquals(27,invalid.points());assertNull(invalid.selection());
        newer.put("revision","lower-costs");((com.fasterxml.jackson.databind.node.ArrayNode)newer.at("/state/points/initial")).set(0,JSON.getNodeFactory().numberNode(7));definitions.add(3,newer);
        var valid=service.load(saved.id(),target(3),alice);assertTrue(valid.valid());assertTrue(valid.revisionChanged());assertEquals(24,valid.savedPoints());assertEquals(21,valid.points());
        assertEquals(List.of("new-tie-id","new-tie-id","new-tie-id"),valid.selection().units());assertEquals(saved,service.get(saved.id(),alice));
        // Snapshot reads remain possible even if the source runtime can no longer be compiled.
        definitions.rows.clear();assertEquals(saved,service.get(saved.id(),alice));
    }
    @Test void missingOrAmbiguousOriginsAndDisallowedPoliciesRejectRatherThanGuess() throws Exception {
        var saved=service.create(saveRequest("Fighters"),alice);
        var missing=original.deepCopy();missing.put("revision","unrelated");((com.fasterxml.jackson.databind.node.ArrayNode)missing.at("/state/pointSources/initial")).set(0,JSON.getNodeFactory().textNode("{}"));definitions.add(2,missing);
        assertEquals("PRESET_UNIT_UNAVAILABLE",service.load(saved.id(),target(2),alice).code());
        var ambiguous=original.deepCopy();ambiguous.put("revision","ambiguous");((com.fasterxml.jackson.databind.node.ArrayNode)ambiguous.at("/state/pointSources/initial")).set(1,ambiguous.at("/state/pointSources/initial/0"));definitions.add(3,ambiguous);
        assertEquals("PRESET_UNIT_AMBIGUOUS",service.load(saved.id(),target(3),alice).code());
        var policy=original.deepCopy();policy.put("revision","rebel-only");
        ((com.fasterxml.jackson.databind.node.ArrayNode)policy.at("/state/rosterPolicies/initial")).set(0,JSON.getNodeFactory().textNode("rebel"));
        ((com.fasterxml.jackson.databind.node.ArrayNode)policy.at("/state/rosterPolicyChoices/initial")).set(0,JSON.createArrayNode().add("rebel"));definitions.add(4,policy);
        assertEquals("ROSTER_POLICY",service.load(saved.id(),target(4),alice).code());assertEquals(saved,service.get(saved.id(),alice));
    }
    @Test void renameAndDeleteUseVersionsAndCannotChangeSavedSelections() throws Exception {
        var saved=service.create(saveRequest("Fighters"),alice);
        var renamed=service.rename(saved.id(),JSON.createObjectNode().put("name","Patrol").put("expectedVersion",0),alice);
        assertEquals(1,renamed.version());assertEquals(saved.snapshot(),renamed.snapshot());assertEquals(saved.selection(),renamed.selection());
        code("STALE_PRESET",()->service.rename(saved.id(),JSON.createObjectNode().put("name","Lost edit").put("expectedVersion",0),alice));
        code("STALE_PRESET",()->service.delete(saved.id(),0,alice));
        code("PRESET_NAME_EXISTS",()->{try{service.create(saveRequest("patrol"),alice);}catch(RuntimeException e){throw e;}catch(Exception e){throw new AssertionError(e);}});
        service.delete(saved.id(),1,alice);assertTrue(service.list(alice).isEmpty());
    }
    @Test void forgedFieldsInvalidCostsAndMalformedNamesCannotBeSaved() throws Exception {
        var owner=saveRequest("Fighters").put("ownerUserId",2);code("REQUEST_FIELDS_INVALID",()->service.create(owner,alice));
        var cost=saveRequest("Fighters").put("points",0);code("REQUEST_FIELDS_INVALID",()->service.create(cost,alice));
        for(String name:List.of(" ","a".repeat(101))) { var request=saveRequest(name);code("PRESET_NAME_INVALID",()->service.create(request,alice)); }
        var empty=saveRequest("Empty");empty.putArray("units");code("ROSTER_COUNT",()->service.create(empty,alice));
        var faction=saveRequest("Wrong faction");faction.putArray("units").add("456");code("ROSTER_FACTION",()->service.create(faction,alice));
        var budget=saveRequest("Too many X-wings").put("policy","rebel");budget.putArray("units").add("456").add("456").add("456");code("ROSTER_BUDGET",()->service.create(budget,alice));
        assertTrue(presets.rows.isEmpty());
    }
    private static final class Definitions extends RulesRepository {
        final Map<Long,Definition> rows=new HashMap<>();Definitions() { super(null); }
        void add(long id,ObjectNode source) {
            var result=new RulesCompiler().compile(source.toString());assertTrue(result.valid(),result.diagnostics().toString());var d=result.definition();
            rows.put(id,new Definition(id,d.id(),d.revision(),1,"published",d.canonicalJson(),d.fingerprint(),d.compilerVersion(),new RulesRuntime(d).version(),"{}",null,null));
        }
        @Override public Optional<Definition> definition(long id,boolean lock) { return Optional.ofNullable(rows.get(id)); }
    }
    private static final class Presets extends RulesFleetPresetRepository {
        final Map<Long,Preset> rows=new LinkedHashMap<>();final Map<Long,Long> owners=new HashMap<>();long next=1;
        Presets() { super(null); }
        @Override public List<Preset> list(long owner) { return rows.values().stream().filter(row->owners.get(row.id())==owner).toList(); }
        @Override public Optional<Preset> find(long owner,long id) { return Objects.equals(owners.get(id),owner)?Optional.ofNullable(rows.get(id)):Optional.empty(); }
        @Override public long create(long owner,long definition,int seat,String name,String policy,String units,String snapshot) {
            if(list(owner).stream().anyMatch(p->p.name().equalsIgnoreCase(name)))throw new org.springframework.dao.DuplicateKeyException("Name",new SQLException("Name","23505"));
            long id=next++;owners.put(id,owner);rows.put(id,new Preset(id,definition,seat,name,0,policy,units,snapshot));return id;
        }
        private Preset current(long owner,long id,long version) { var row=find(owner,id).orElseThrow();if(row.version()!=version)throw new org.springframework.dao.CannotSerializeTransactionException("Stale",new SQLException("Stale","40001"));return row; }
        @Override public void rename(long owner,long id,long version,String name) { var p=current(owner,id,version);rows.put(id,new Preset(id,p.definitionId(),p.seat(),name,version+1,p.policy(),p.units(),p.snapshot())); }
        @Override public void delete(long owner,long id,long version) { current(owner,id,version);rows.remove(id);owners.remove(id); }
    }
}
