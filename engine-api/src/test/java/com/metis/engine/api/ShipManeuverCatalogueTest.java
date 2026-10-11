package com.metis.engine.api;

import com.fasterxml.jackson.databind.node.*;
import com.metis.engine.core.rules.RulesCompiler;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.api.RulesCatalogueTest.JSON;
import static org.junit.jupiter.api.Assertions.*;

class ShipManeuverCatalogueTest {
    static ObjectNode resource(String suffix) throws Exception {
        try(var stream=ShipManeuverCatalogueTest.class.getResourceAsStream("/rules/catalogue/armada-ship-maneuvers"+suffix+".json")) { return (ObjectNode)JSON.readTree(stream); }
    }
    static ObjectNode source() throws Exception {
        var snapshot=JSON.createObjectNode();var units=snapshot.putArray("units");var template=resource("");
        for(int i=0;i<2;i++) {
            var unit=units.addObject().put("displayName",template.at("/state/names/initial/"+i).textValue()).put("classification","Ship").put("code","ship-"+i)
                    .put("factionCode",template.at("/state/factions/initial/"+i).textValue()).put("maximumHullPoints",4).put("maximumMovementDistance",4).put("pointsCost",44).put("uniqueKey","");
            unit.put("defenseTokenCount",3);unit.putArray("antiSquadronDice"); // Explicitly outside this recipe's movement-only scope.
            unit.putObject("pointsSource").put("repository","fixture").put("commit","a".repeat(40)).put("file","ships.cat").put("revision","1").put("entryId","ship-"+i)
                    .put("sha256","b".repeat(64)).put("url","https://example.test/ships.cat").put("licence","fixture").put("rawPoints","44.0");
            unit.set("maneuverChart",template.at("/state/charts/initial/"+i).deepCopy());
            unit.set("maneuverGeometry",JSON.readTree("{\"unit\":\"tts\",\"width\":2.796,\"height\":1.614,\"segment\":2.6265,\"angle\":22.5,\"anchor\":[0.618,1.127],\"toolWidth\":0.64}"));
            var provenance=unit.putObject("maneuverSource").put("repository","fixture").put("commit","c".repeat(40)).put("file","ships.lua").put("sha256","d".repeat(64)).put("url","https://example.test/ships.lua").put("licence","fixture");
            provenance.putArray("calibration").addObject().put("file","ruler.lua").put("sha256","e".repeat(64));
        }
        return snapshot;
    }
    @Test void capturedCostsChartsAndGeometryPopulateTheSelectableDefinition() throws Exception {
        var template=resource("");var snapshot=source();RulesCatalogueService.populate(template,snapshot,resource(".pack"));
        var compiled=new RulesCompiler().compile(template.toString());assertTrue(compiled.valid(),compiled.diagnostics().toString());
        var rosters=new CatalogueRosters(compiled.definition());rosters.validateDefaults();assertTrue(rosters.view().selectable());assertEquals(List.of(44L,44L),rosters.view().units().stream().map(CatalogueRosters.Unit::points).toList());
        assertEquals(List.of("rebel","imperial"),rosters.view().seats().getFirst().choices().stream().map(CatalogueRosters.Policy::code).toList());
        assertEquals(JSON.readTree("[0,1,1,1]"),template.at("/state/charts/initial/1/4"));assertEquals(2.796,template.at("/space/bodies/ship/width").doubleValue());
        assertEquals("tts",template.at("/space/boards/table/unit").textValue());assertEquals(snapshot.at("/units/0/maneuverSource"),JSON.readTree(template.at("/state/maneuverSources/initial/0").textValue()));
        assertEquals("ROSTER_COUNT",rosters.validate(0,"rebel",JSON.readTree("[\"ship-0\",\"ship-0\"]")).code());assertEquals("ROSTER_FACTION",rosters.validate(0,"rebel",JSON.readTree("[\"ship-1\"]")).code());
    }
    @Test void measurementsAndChartsAreImportedFromDataRatherThanShipIdentity() throws Exception {
        var template=resource("");var snapshot=source();var first=(ObjectNode)snapshot.at("/units/0");first.put("maximumMovementDistance",2).put("maximumHullPoints",7);
        first.set("maneuverChart",JSON.readTree("[[],[1],[0,1]]"));
        for(var unit:snapshot.get("units"))((ObjectNode)unit.get("maneuverGeometry")).put("segment",3.5).put("width",3.0);
        RulesCatalogueService.populate(template,snapshot,resource(".pack"));assertEquals(2,template.at("/state/maxSpeeds/initial/0").intValue());assertEquals(7,template.at("/state/hulls/initial/0").intValue());
        assertEquals(3.5,template.at("/state/segment/initial").doubleValue());assertEquals(3,template.at("/space/bodies/ship/width").doubleValue());assertEquals(JSON.readTree("[[],[1],[0,1]]"),template.at("/state/charts/initial/0"));
    }
    @Test void missingMalformedOrMismatchedMeasurementsCannotSilentlyDefault() throws Exception {
        var cases=List.of(new String[]{"maximumMovementDistance","2.5","CATALOGUE_SPEED_INVALID"},new String[]{"maximumMovementDistance","9","CATALOGUE_SPEED_INVALID"},
                new String[]{"maximumHullPoints","0","CATALOGUE_HULL_INVALID"},new String[]{"maneuverChart","null","CATALOGUE_MANEUVER_CHART"},new String[]{"maneuverChart","[[0],[2],[1,2],[0,1,2],[0,1,1,2]]","CATALOGUE_MANEUVER_CHART"},
                new String[]{"maneuverChart","[[],[2],[1,-1],[0,1,2],[0,1,1,2]]","CATALOGUE_MANEUVER_CHART"},new String[]{"maneuverSource","{}","CATALOGUE_MANEUVER_SOURCE"},
                new String[]{"maneuverGeometry","{}","CATALOGUE_GEOMETRY_INVALID"},new String[]{"pointsCost","null","CATALOGUE_POINTS_MISSING"});
        for(var item:cases) { var snapshot=source();((ObjectNode)snapshot.at("/units/0")).set(item[0],JSON.readTree(item[1]));assertEquals(item[2],assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(resource(""),snapshot,resource(".pack"))).code()); }
        var mismatch=source();((ObjectNode)mismatch.at("/units/1/maneuverGeometry")).put("width",9);assertEquals("CATALOGUE_FOOTPRINT_MISMATCH",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(resource(""),mismatch,resource(".pack"))).code());
        for(String field:List.of("segment","angle","toolWidth")) { var bad=source();((ObjectNode)bad.at("/units/0/maneuverGeometry")).put(field,-1);assertEquals("CATALOGUE_GEOMETRY_INVALID",assertThrows(RulesService.Failure.class,()->RulesCatalogueService.populate(resource(""),bad,resource(".pack"))).code()); }
    }
}
