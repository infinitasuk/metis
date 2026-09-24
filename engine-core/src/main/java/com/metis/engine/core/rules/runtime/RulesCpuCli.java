package com.metis.engine.core.rules.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metis.engine.core.rules.*;
import java.nio.file.*;
import java.util.*;

/** Headless baseline CPU demo; the operational command cap never creates a rules result. */
public final class RulesCpuCli {
    private RulesCpuCli() { }
    public record Run(String definition,String fingerprint,long commands,String outcome,List<String> winners,int round) { }
    public static Run play(String source,int maximum) {
        if(maximum<1||maximum>10000)throw new IllegalArgumentException("Command limit must be between 1 and 10000");
        var compiled=new RulesCompiler().compile(source);if(!compiled.valid())throw new IllegalArgumentException(compiled.diagnostics().toString());
        var runtime=new RulesRuntime(compiled.definition());var initial=runtime.initial("cpu-demo",List.of("alice","bob"));var first=runtime.start(initial,0);
        if(!first.accepted())throw new IllegalArgumentException(first.code());var state=first.state();var cpu=new RulesCpu();
        for(int i=0;i<maximum&&state.control().status()!=RulesState.Status.FINISHED;i++) {
            RulesCpu.Command command=null;
            for(String actor:state.players()) { var selected=cpu.choose(runtime.decisionInput(state,actor));if(selected.isPresent()) { command=selected.get();break; } }
            if(command==null)throw new IllegalStateException("No projected command is available at version "+state.version());
            var result=switch(command.type()) {
                case "act"->runtime.act(state,command.actor(),command.action(),command.parameters(),command.expectedVersion());
                case "respond"->runtime.respond(state,command.actor(),command.decision(),command.option(),command.expectedVersion());
                case "activate"->runtime.activate(state,command.actor(),command.entity(),command.expectedVersion());
                case "advanceStep"->runtime.advanceStep(state,command.actor(),command.expectedVersion());
                case "pass"->runtime.pass(state,command.actor(),command.expectedVersion());
                default->throw new IllegalStateException("Unsupported CPU command");
            };
            if(!result.accepted())throw new IllegalStateException("Projected command rejected: "+result.code());
            if(!RulesRuntime.replay(state,result.commit()).equals(result.state()))throw new IllegalStateException("Replay differs from commit");
            state=runtime.restore(RulesStateCodec.encode(result.state()));
        }
        if(state.control().status()!=RulesState.Status.FINISHED)throw new IllegalStateException("Operational command limit reached without a rules ending");
        return new Run(compiled.definition().id(),compiled.definition().fingerprint(),state.version(),state.control().outcome(),state.control().winners(),state.control().flow().round());
    }
    public static void main(String[] args) throws Exception {
        if(args.length<1||args.length>2)throw new IllegalArgumentException("Usage: RulesCpuCli definition.json [maximum-commands]");
        System.out.println(new ObjectMapper().writeValueAsString(play(Files.readString(Path.of(args[0])),args.length==2?Integer.parseInt(args[1]):300)));
    }
}
