package com.metis.engine.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import com.metis.engine.core.rules.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.api.RulesCatalogueTest.JSON;
import static org.junit.jupiter.api.Assertions.*;

class NavigationCatalogueTest {
    static ObjectNode pack() throws Exception { try(var stream=NavigationCatalogueTest.class.getResourceAsStream("/rules/catalogue/armada-ship-navigation.pack.json")) { return (ObjectNode)JSON.readTree(stream); } }
    static ObjectNode template() throws Exception { return (ObjectNode)JSON.readTree(RulesCatalogueService.compose(ShipManeuverCatalogueTest.resource(""),pack())); }
    static ObjectNode source() throws Exception {
        var source=ShipManeuverCatalogueTest.source();
        for(var value:source.get("units")) {
            var unit=(ObjectNode)value;unit.put("commandValue",1);var command=unit.putObject("commandSource").put("rawValue","1");command.set("provenance",unit.get("pointsSource").deepCopy());
            command.putObject("characteristic").put("catalogue","ships.cat").put("revision","1").put("profile",unit.get("displayName").textValue()).put("characteristic","Command");
        }
        return source;
    }
    @Test void importsSourceCommandValuesAndPreservesTheOlderManeuverDefinition() throws Exception {
        var old=ShipManeuverCatalogueTest.resource("");RulesCatalogueService.populate(old,ShipManeuverCatalogueTest.source(),ShipManeuverCatalogueTest.resource(".pack"));String fingerprint=new RulesCompiler().compile(old.toString()).definition().fingerprint();
        var template=template();var source=source();RulesCatalogueService.populate(template,source,pack());var compiled=new RulesCompiler().compile(template.toString());assertTrue(compiled.valid(),compiled.diagnostics().toString());
        assertEquals(JSON.readTree("[1,1]"),template.at("/state/commandRatings/initial"));assertEquals(source.at("/units/0/commandSource"),JSON.readTree(template.at("/state/commandSources/initial/0").textValue()));
        var rosters=new CatalogueRosters(compiled.definition());rosters.validateDefaults();assertEquals(List.of(44L,44L),rosters.view().units().stream().map(CatalogueRosters.Unit::points).toList());
        var runtime=new RulesRuntime(compiled.definition());var state=rosters.select(runtime.initial("navigation-import",List.of("alice","bob")),JSON.readTree("[{\"policy\":\"imperial\",\"units\":[\"ship-1\"]},{\"policy\":\"rebel\",\"units\":[\"ship-0\"]}]"));
        var started=runtime.start(state,0);assertTrue(started.accepted(),started.message());assertEquals(1,started.state().entities().get("seat0-ship0").attributes().get("commandValue").integer());
        assertEquals(CompiledRules.Audience.OWNER,compiled.definition().entities().get("ship").attributes().get("orders").visibility());
        assertEquals(fingerprint,new RulesCompiler().compile(old.toString()).definition().fingerprint());assertFalse(old.path("state").has("commandRatings"));
    }
    @Test void ratingChangesFollowCapturedDataAndMissingOrMismatchedProvenanceRejectsImport() throws Exception {
        var source=source();((ObjectNode)source.at("/units/0")).put("commandValue",2);((ObjectNode)source.at("/units/0/commandSource")).put("rawValue","2");var template=template();RulesCatalogueService.populate(template,source,pack());assertEquals(2,template.at("/state/commandRatings/initial/0").intValue());
        for(String invalid:List.of("null","0","-1","5","1.5","\"1\"","9223372036854775807")) {
            var bad=source();((ObjectNode)bad.at("/units/0")).set("commandValue",JSON.readTree(invalid));assertEquals("CATALOGUE_COMMAND_VALUE",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),bad,pack())).code());
        }
        for(String pointer:List.of("/units/0/commandSource/rawValue","/units/0/commandSource/characteristic/revision","/units/0/commandSource/characteristic/catalogue","/units/0/commandSource/characteristic/profile","/units/0/commandSource/characteristic/characteristic","/units/0/commandSource/provenance/sha256")) {
            var bad=source();var slash=pointer.lastIndexOf('/');((ObjectNode)bad.at(pointer.substring(0,slash))).put(pointer.substring(slash+1),"invalid");
            assertEquals("CATALOGUE_COMMAND_SOURCE",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),bad,pack())).code());
        }
        var mismatch=source();((ObjectNode)mismatch.at("/units/0/commandSource")).put("rawValue","2");assertEquals("CATALOGUE_COMMAND_SOURCE",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),mismatch,pack())).code());
    }
    @Test void navigationImportsRejectUnsupportedYawAndMissingCommandSources() throws Exception {
        var bad=source();((ObjectNode)bad.at("/units/0")).remove("commandSource");assertEquals("CATALOGUE_COMMAND_SOURCE",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),bad,pack())).code());
        var yaw=source();((com.fasterxml.jackson.databind.node.ArrayNode)yaw.at("/units/0/maneuverChart/1")).set(0,JSON.getNodeFactory().numberNode(3));
        assertEquals("CATALOGUE_SCOPE_UNSUPPORTED",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(template(),yaw,pack())).code());
    }
}
