package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.*;
import com.metis.engine.core.rules.*;
import java.nio.file.*;
import java.util.*;

/** Data-only scripted decision provider for headless demonstrations and acceptance fixtures. */
public final class RulesPlayCli {
    private RulesPlayCli() { }
    public record Run(RulesState initial,RulesState state,List<RulesState.Commit> commits,int rejected) {
        public Run { commits=List.copyOf(commits); }
    }
    public static Run play(RulesRuntime runtime,JsonNode script,boolean restoreEachStep) {
        var players=new ArrayList<String>();script.required("players").forEach(value->players.add(value.textValue()));
        RulesState initial=runtime.initial(script.required("session").textValue(),players),state=initial;
        var commits=new ArrayList<RulesState.Commit>();int rejected=0;
        for(var step:script.required("steps")) {
            var result=submit(runtime,state,step);
            if(step.has("expect")) {
                if(!step.get("expect").textValue().equals(result.code()))throw new IllegalStateException("Unexpected result: "+result.code()+" / "+result.message());
            } else if(!result.accepted())throw new IllegalStateException("Rejected script step: "+result.code()+" / "+result.message());
            if(result.accepted()) {
                commits.add(result.commit());state=restoreEachStep?runtime.restore(RulesStateCodec.encode(result.state())):result.state();
            } else rejected++;
        }
        var expected=script.required("expected");var winners=new ArrayList<String>();expected.required("winners").forEach(value->winners.add(value.textValue()));
        if(state.control().status()!=RulesState.Status.FINISHED || !expected.required("outcome").textValue().equals(state.control().outcome()) || !winners.equals(state.control().winners()) || expected.required("round").intValue()!=state.control().flow().round())
            throw new IllegalStateException("Script did not reach its expected terminal state");
        return new Run(initial,state,commits,rejected);
    }
    public static RulesState.Result submit(RulesRuntime runtime,RulesState state,JsonNode step) {
        String command=step.required("command").textValue(),actor=step.path("actor").asText();
        return switch(command) {
            case "start" -> runtime.start(state,state.version());
            case "respond" -> {
                var decision=state.control().decision();if(decision==null)throw new IllegalArgumentException("No scripted decision pending");
                var choice=RulesStateCodec.literal(decision.options().getFirst().type(),step.required("choice"));
                yield runtime.respond(state,actor,decision.id(),decision.options().indexOf(choice),state.version());
            }
            case "act" -> {
                String action=step.required("action").textValue();var declared=runtime.definition().actions().get(action);
                if(declared==null)throw new IllegalArgumentException("Unknown scripted action");
                var arguments=new TreeMap<String,RuleValue>();var inputs=step.required("args");
                inputs.fields().forEachRemaining(entry->{var type=declared.parameters().get(entry.getKey());if(type==null)throw new IllegalArgumentException("Unknown scripted parameter");arguments.put(entry.getKey(),RulesStateCodec.literal(type,entry.getValue()));});
                yield runtime.act(state,actor,action,arguments,state.version());
            }
            case "activate" -> runtime.activate(state,actor,step.required("entity").textValue(),state.version());
            case "pass" -> runtime.pass(state,actor,state.version());
            case "advanceStep" -> runtime.advanceStep(state,actor,state.version());
            default -> throw new IllegalArgumentException("Unknown script command: "+command);
        };
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Provide definition.json and script.json");
        var compiled=new RulesCompiler().compile(Files.readString(Path.of(args[0])));
        if(!compiled.valid())throw new IllegalArgumentException(compiled.diagnostics().toString());
        var run=play(new RulesRuntime(compiled.definition()),new ObjectMapper().readTree(Files.readString(Path.of(args[1]))),true);
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "definition",compiled.definition().id(),"fingerprint",compiled.definition().fingerprint(),"status",run.state().control().status(),
                "outcome",run.state().control().outcome(),"winners",run.state().control().winners(),"round",run.state().control().flow().round(),
                "acceptedTransitions",run.commits().size(),"rejectedSteps",run.rejected(),"events",run.state().events().size())));
    }
}
