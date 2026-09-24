package com.metis.engine.core.rules.runtime;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import static com.metis.engine.core.rules.runtime.RuntimeFixtures.*;
import static com.metis.engine.core.rules.runtime.SpatialConformanceTest.*;
import static com.metis.engine.core.rules.runtime.SpatialGeometry.*;
import static com.metis.engine.core.rules.runtime.SpatialGeometryTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialPropertiesTest {
    @Property(tries=100,seed="20260925") void circlesAgreeWithSquaredDistanceOracle(@ForAll @IntRange(min=-20,max=20) int x,@ForAll @IntRange(min=-20,max=20) int y,@ForAll @IntRange(min=1,max=8) int radius) {
        var a=circle(0,0,2);var b=circle(x,y,radius);double square=(double)x*x+(double)y*y,limit=(radius+2.0)*(radius+2.0);
        assertEquals(square<=limit,overlap(a,b,false,E));assertEquals(square<limit,overlap(a,b,true,E));assertEquals(overlap(a,b,true,E),overlap(b,a,true,E));
        assertEquals(Math.max(0,Math.sqrt(square)-radius-2),edgeDistance(a,b),E);
    }
    @Property(tries=100,seed="20260926") void gridLegalityMatchesIndependentCellModel(@ForAll @IntRange(min=-2,max=3) int dx,@ForAll @IntRange(min=-2,max=3) int dy) throws Exception {
        var runtime=runtime(example("grid-skirmish"));var state=start(runtime);state=accepted(runtime.activate(state,"alice","alice-a",state.version()));var parameters=move("piece","alice-a",0,1+dx,1+dy);
        boolean expected=1+dx>=0&&1+dx<9&&1+dy>=0&&1+dy<5&&Math.abs(dx)+Math.abs(dy)<=1;
        var saved=RulesStateCodec.encode(state);var preview=runtime.preview(state,"alice","move",parameters);var result=runtime.act(state,"alice","move",parameters,state.version());
        assertEquals(expected,preview.allowed());assertEquals(expected,result.accepted());assertEquals(saved,RulesStateCodec.encode(state));
        if(expected) { assertEquals(1+dx,result.state().entities().get("alice-a").attributes().get("x").integer());assertEquals(1+dy,result.state().entities().get("alice-a").attributes().get("y").integer());assertEquals(result.state(),RulesRuntime.replay(state,result.commit())); }
        else { assertEquals(preview.code(),result.code());assertNull(result.commit()); }
    }
}
