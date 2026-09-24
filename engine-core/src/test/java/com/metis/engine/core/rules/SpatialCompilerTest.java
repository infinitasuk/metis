package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.metis.engine.core.rules.LanguageFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialCompilerTest {
    static void bad(ObjectNode source,String code) { var result=new RulesCompiler().compile(source.toString());assertFalse(result.valid());assertEquals(code,result.diagnostics().getFirst().code(),result.diagnostics().toString()); }
    @Test void spatialDefinitionsRejectInvalidCapabilitiesBindingsAndUnits() throws Exception {
        var cases=List.of(
            new String[]{"/modules/space","null","MODULE_NOT_DECLARED"},
            new String[]{"/space/boards/table/width","9.5","GRID_DIMENSIONS"},
            new String[]{"/space/bodies/piece/board","\"unknown\"","BOARD_UNKNOWN"},
            new String[]{"/space/bodies/piece/y","\"x\"","SPATIAL_FIELD"},
            new String[]{"/space/bodies/piece/placed","\"unknown\"","SPATIAL_FIELD"},
            new String[]{"/entities/piece/attributes/x/visibility","\"OWNER\"","SPATIAL_FIELD"},
            new String[]{"/space/bodies/piece/opaque","\"x\"","TYPE_MISMATCH"},
            new String[]{"/space/bodies/piece/placed","\"x\"","TYPE_MISMATCH"},
            new String[]{"/entities/piece/attributes/x/type","\"decimal\"","GRID_FIELD"},
            new String[]{"/entities/piece/attributes/heading/type","\"integer\"","SPATIAL_FIELD"},
            new String[]{"/space/bodies/piece/shape","\"CIRCLE\"","GRID_FOOTPRINT"},
            new String[]{"/space/movements/walk/allowance","\"absent\"","MOVEMENT_ALLOWANCE"},
            new String[]{"/space/zones/west/width","10","ZONE_BOUNDS"},
            new String[]{"/space/zones/west/board","\"unknown\"","BOARD_UNKNOWN"},
            new String[]{"/actions/move/choices/heading","{\"var\":\"path\"}","BINDING_UNKNOWN"},
            new String[]{"/actions/move/choices/extra","true","CHOICE_PARAMETER"},
            new String[]{"/actions/move/procedure","\"score\"","MOVEMENT_PREVIEW"});
        for(var change:cases) { var source=example("grid-skirmish");edit(source,change[0],JSON.readTree(change[1]),change[1].equals("null"));bad(source,change[2]); }
        var continuous=example("vector-orders");edit(continuous,"/space/boards/table/metric",JSON.readTree("\"MANHATTAN\""),false);bad(continuous,"SPACE_METRIC");
        continuous=example("vector-orders");edit(continuous,"/space/bodies/ship/radius",JSON.readTree("0"),false);bad(continuous,"FOOTPRINT_INVALID");
        edit(continuous,"/space/bodies/ship/shape",JSON.readTree("\"RECTANGLE\""),false);bad(continuous,"FOOTPRINT_INVALID");
        edit(continuous,"/space/bodies/ship/width",JSON.readTree("1"),false);edit(continuous,"/space/bodies/ship/height",JSON.readTree("2"),false);compile(continuous);
        continuous=example("vector-orders");((ObjectNode)continuous.at("/actions/attack")).put("preview","MOVEMENT");bad(continuous,"MOVEMENT_PREVIEW");
    }
    @Test void scenarioPoliciesRejectBadResourceAndRosterContracts() throws Exception {
        var cases=List.of(
            new String[]{"/modules/scenario","null","MODULE_NOT_DECLARED"},
            new String[]{"/scenario/rosters/squad/entityType","\"missing\"","ENTITY_TYPE_UNKNOWN"},
            new String[]{"/scenario/rosters/squad/points","\"alive\"","ROSTER_FIELD"},
            new String[]{"/scenario/rosters/squad/unique","\"heading\"","ROSTER_FIELD"},
            new String[]{"/scenario/rosters/squad/maximum","1","ROSTER_BOUNDS"},
            new String[]{"/scenario/rosters/squad/maximum","100","ROSTER_BOUNDS"},
            new String[]{"/scenario/damage/wounds/entityType","\"missing\"","ENTITY_TYPE_UNKNOWN"},
            new String[]{"/scenario/damage/wounds/absorbers","[\"health\"]","DAMAGE_BINDING"},
            new String[]{"/scenario/damage/wounds/absorbers","[\"missing\"]","DAMAGE_BINDING"},
            new String[]{"/scenario/damage/wounds/marker","\"heading\"","DAMAGE_BINDING"});
        for(var change:cases) { var source=example("grid-skirmish");edit(source,change[0],JSON.readTree(change[1]),change[1].equals("null"));bad(source,change[2]); }
        var source=example("grid-skirmish");((ObjectNode)source.at("/scenario/damage/wounds")).put("onZero","KEEP");compile(source);
        ((ObjectNode)source.at("/space/bodies")).set("missing",source.at("/space/bodies/piece").deepCopy());bad(source,"ENTITY_TYPE_UNKNOWN");
    }
}
