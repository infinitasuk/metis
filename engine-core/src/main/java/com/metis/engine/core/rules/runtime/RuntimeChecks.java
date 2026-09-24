package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.*;
import java.util.*;

final class RuntimeChecks {
    private RuntimeChecks() { }
    static void require(boolean valid,String code,String path,String message) {
        if(!valid)throw new RuleEvaluationException(code,path,message);
    }
    static RuleValue value(RuleValue value,RuleType type,CompiledRules rules,List<String> players,
                           Map<String,EvaluationContext.Entity> entities,EvaluationContext.Budget budget,String path,boolean references) {
        budget.spend(path);
        require(value!=null&&type.accepts(value.type()),"VALUE_TYPE",path,"Value does not match declared type");
        var converted=value.as(type);
        if(type.kind()==RuleType.Kind.LIST) {
            require(converted.items().size()<=rules.limits().maxCollectionSize(),"COLLECTION_LIMIT",path,"List exceeds configured limit");
            for(var item:converted.items())value(item,type.element(),rules,players,entities,budget,path,references);
        } else if(references && type.kind()==RuleType.Kind.PLAYER) {
            require(players.contains((String)converted.value()),"PLAYER_UNKNOWN",path,"Unknown player reference");
        } else if(references && type.kind()==RuleType.Kind.ENTITY) {
            var ref=(RuleValue.EntityRef)converted.value();var entity=entities.get(ref.id());
            require(entity!=null&&entity.type().equals(ref.type()),"ENTITY_NOT_FOUND",path,"Entity reference does not resolve");
        }
        return converted;
    }
    static void resource(CompiledRules.Resource definition,long amount,String path) {
        require(amount>=definition.minimum()&&amount<=definition.maximum(),"RESOURCE_BOUNDS",path,"Resource amount is outside declared bounds");
    }
}
