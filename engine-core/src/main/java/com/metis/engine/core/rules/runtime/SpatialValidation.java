package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.CompiledRules;
import static com.metis.engine.core.rules.runtime.RuntimeChecks.require;

final class SpatialValidation {
    private SpatialValidation() { }
    static void validate(CompiledRules rules,RulesState state) {
        for(var entity:state.entities().values()) {
            var body=rules.space().bodies().get(entity.type());if(body==null)continue;
            double x=entity.attributes().get(body.x()).number().doubleValue(),y=entity.attributes().get(body.y()).number().doubleValue(),heading=entity.attributes().get(body.facing()).number().doubleValue();
            require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(heading),"POSITION_INVALID","/space","Pose must be finite");
            if(entity.attributes().get(body.placed()).bool())SpatialMachine.validatePosition(body,rules.space().boards().get(body.board()),new SpatialGeometry.Point(x,y),heading);
        }
    }
}
