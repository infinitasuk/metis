package com.metis.engine.core.rules.runtime;

import com.metis.engine.core.rules.RuleValue;
import java.util.*;

/** Deterministic baseline policy. Its entire input is the acting seat's view and offered forms. */
public final class RulesCpu {
    public record Input(String actor,RulesView view,List<RulesRuntime.OfferedAction> actions,List<String> controls,List<String> entities) {
        public Input { actions=List.copyOf(actions);controls=List.copyOf(controls);entities=List.copyOf(entities); }
    }
    public record Command(String type,String actor,String action,Map<String,RuleValue> parameters,String entity,String decision,int option,long expectedVersion) {
        public Command { parameters=Map.copyOf(parameters); }
    }
    public Optional<Command> choose(Input input) {
        var view=input.view();if(view.status()==RulesState.Status.FINISHED)return Optional.empty();
        if(view.decision()!=null) {
            if(!view.decision().player().equals(input.actor())||view.decision().options().isEmpty())return Optional.empty();
            return Optional.of(new Command("respond",input.actor(),null,Map.of(),null,view.decision().id(),0,view.version()));
        }
        if(input.controls().contains("activate")&&!input.entities().isEmpty())return Optional.of(new Command("activate",input.actor(),null,Map.of(),input.entities().getFirst(),null,0,view.version()));
        var selected=input.actions().stream().sorted(Comparator.comparingInt(RulesRuntime.OfferedAction::priority).reversed().thenComparing(RulesRuntime.OfferedAction::action)).findFirst();
        if(selected.isPresent()) { var action=selected.get();return Optional.of(new Command("act",input.actor(),action.action(),action.parameters(),null,null,0,view.version())); }
        for(String control:List.of("advanceStep","pass"))if(input.controls().contains(control))return Optional.of(new Command(control,input.actor(),null,Map.of(),null,null,0,view.version()));
        return Optional.empty();
    }
}
