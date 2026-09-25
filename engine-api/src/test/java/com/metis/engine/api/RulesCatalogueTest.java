package com.metis.engine.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.RulesCompiler;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RulesCatalogueTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static ObjectNode resource(String suffix) throws Exception { try(var in=RulesCatalogueTest.class.getResourceAsStream("/rules/catalogue/armada-squadron-training"+suffix+".json")) { assertNotNull(in);return (ObjectNode)JSON.readTree(in); } }
    static ObjectNode m8Resource(String suffix) throws Exception { try(var in=RulesCatalogueTest.class.getResourceAsStream("/rules/catalogue/armada-squadron-training-m8"+suffix+".json")) { assertNotNull(in);return (ObjectNode)JSON.readTree(in); } }
    static ObjectNode source() throws Exception { return (ObjectNode)JSON.readTree("""
      {"units":[
       {"displayName":"TIE Fighter Squadron","classification":"Squadron","code":"123","factionCode":"Galactic Empire","maximumHullPoints":3,"maximumMovementDistance":4,"baseRadius":25,"defenseTokenCount":0,"antiSquadronDice":[{"color":"Blue","count":3}]},
       {"displayName":"X-Wing Squadron","classification":"Squadron","code":"456","factionCode":"Rebel Alliance","maximumHullPoints":5,"maximumMovementDistance":3,"baseRadius":25,"defenseTokenCount":0,"antiSquadronDice":[{"color":"Blue","count":4}]}
      ]}
      """); }
    @Test void importUsesCapturedProfileValuesRatherThanVariantIdentityBranches() throws Exception {
        var template=resource("");var source=source();((ObjectNode)source.at("/units/0")).put("maximumHullPoints",7).put("maximumMovementDistance",2);RulesCatalogueService.populate(template,source,resource(".pack"));
        assertEquals(7,template.at("/state/hulls/initial/0").intValue());assertEquals(200,template.at("/state/speeds/initial/0").intValue());assertEquals("123",template.at("/state/catalogueIds/initial/0").textValue());assertEquals(4,template.at("/state/pools/initial/1").size());assertTrue(new RulesCompiler().compile(template.toString()).valid());
    }
    @Test void unsupportedAndMissingProfilesAreRejectedInsteadOfDefaulted() throws Exception {
        var cases=List.of(new String[]{"displayName","\"Other\"","CATALOGUE_PROFILE_MISMATCH"},new String[]{"classification","\"Ship\"","CATALOGUE_PROFILE_MISMATCH"},
            new String[]{"maximumHullPoints","0","CATALOGUE_HULL_INVALID"},new String[]{"maximumHullPoints","101","CATALOGUE_HULL_INVALID"},new String[]{"maximumMovementDistance","0","CATALOGUE_GEOMETRY_INVALID"},
            new String[]{"maximumMovementDistance","\"unknown\"","CATALOGUE_PROFILE_MISSING"},new String[]{"baseRadius","0","CATALOGUE_GEOMETRY_INVALID"},new String[]{"baseRadius","24","CATALOGUE_FOOTPRINT_MISMATCH"},
            new String[]{"defenseTokenCount","1","CATALOGUE_SCOPE_UNSUPPORTED"},new String[]{"defenseTokenCount","4294967296","CATALOGUE_SCOPE_UNSUPPORTED"},
            new String[]{"antiSquadronDice","[]","CATALOGUE_POOL_INVALID"},new String[]{"antiSquadronDice","[{\"color\":\"Red\",\"count\":3}]","CATALOGUE_POOL_INVALID"},new String[]{"antiSquadronDice","[{\"color\":\"Blue\",\"count\":65}]","CATALOGUE_POOL_INVALID"});
        for(var item:cases) { var source=source();((ObjectNode)source.at("/units/0")).set(item[0],JSON.readTree(item[1]));var error=assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(resource(""),source,resource(".pack")));assertEquals(item[2],error.code()); }
        var missing=source();((com.fasterxml.jackson.databind.node.ArrayNode)missing.get("units")).remove(1);assertEquals("CATALOGUE_PROFILE_MISSING",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(resource(""),missing,resource(".pack"))).code());
    }

    @Test void m8PackCarriesDefinitionOwnedRosterAndDeploymentPolicy() throws Exception {
        var template=m8Resource("");RulesCatalogueService.populate(template,source(),m8Resource(".pack"));
        var result=new RulesCompiler().compile(template.toString());assertTrue(result.valid(),result.diagnostics().toString());
        assertEquals("deployment",template.at("/flow/initial").textValue());
        assertEquals(0,template.at("/state/points/initial/0").intValue());
        assertEquals("deployment0",template.at("/state/deploymentZones/initial/0").textValue());
        assertEquals("fleet",template.at("/procedures/prepare/steps/1/steps/2/args/policy").textValue());
        assertEquals(0,template.at("/space/zones/deployment0/ownerSeat").intValue());
        assertEquals(1,template.at("/space/zones/deployment1/ownerSeat").intValue());
    }
}
