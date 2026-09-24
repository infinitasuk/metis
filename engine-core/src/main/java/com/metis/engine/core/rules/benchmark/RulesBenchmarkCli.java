package com.metis.engine.core.rules.benchmark;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metis.engine.core.rules.*;
import com.metis.engine.core.rules.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Versioned fixture benchmark. Wall-clock gates run only in the designated CI job. */
public final class RulesBenchmarkCli {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static volatile Object sink;
    private RulesBenchmarkCli() { }
    private static JsonNode resource(String name) throws Exception { try(var in=RulesBenchmarkCli.class.getResourceAsStream(name)) { if(in==null)throw new IllegalStateException(name);return JSON.readTree(in); } }
    private static RulesState accepted(RulesState.Result result) { if(!result.accepted())throw new IllegalStateException(result.code());return result.state(); }
    private static Map<String,Object> measure(String name,Supplier<?> operation,JsonNode config) {
        for(int i=0;i<config.get("warmups").intValue();i++)sink=operation.get();int count=config.get("iterations").intValue();var samples=new double[count];
        for(int i=0;i<count;i++) { long begin=System.nanoTime();sink=operation.get();samples[i]=(System.nanoTime()-begin)/1_000_000.0; }
        Arrays.sort(samples);return Map.of("name",name,"iterations",count,"p50Ms",samples[count/2],"p95Ms",samples[(int)Math.ceil(count*.95)-1],"limitMs",config.get("p95Milliseconds").get(name).doubleValue());
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: enforce-wall-clock output.json");boolean enforce=Boolean.parseBoolean(args[0]);
        var config=resource("/rules/benchmarks/release-v1.json");String source=resource("/rules/examples/"+config.get("fixture").textValue()+".json").toString();var compiler=new RulesCompiler();var compiled=compiler.compile(source);if(!compiled.valid())throw new IllegalStateException(compiled.diagnostics().toString());
        var runtime=new RulesRuntime(compiled.definition());var initial=runtime.initial("benchmark",List.of("alice","bob"));var started=accepted(runtime.start(initial,0));var state=accepted(runtime.activate(started,"alice","alice-a",started.version()));
        var action=runtime.offeredActions(state,"alice").getFirst();var committed=runtime.act(state,"alice",action.action(),action.parameters(),state.version());accepted(committed);String encoded=RulesStateCodec.encode(committed.state());
        var measures=new ArrayList<Map<String,Object>>();measures.add(measure("compile",()->compiler.compile(source),config));measures.add(measure("offers",()->runtime.offeredActions(state,"alice"),config));
        measures.add(measure("preview",()->runtime.preview(state,"alice",action.action(),action.parameters()),config));measures.add(measure("submit",()->runtime.act(state,"alice",action.action(),action.parameters(),state.version()),config));
        measures.add(measure("restore",()->runtime.restore(encoded),config));measures.add(measure("replay",()->RulesRuntime.replay(state,committed.commit()),config));
        var race=(ObjectNode)resource("/rules/examples/resource-race.json");((ObjectNode)race.at("/state/target")).put("initial",200);var historyRuntime=new RulesRuntime(compiler.compile(race.toString()).definition());var h=historyRuntime.initial("history",List.of("alice","bob"));h=accepted(historyRuntime.start(h,0));
        for(int i=0;i<config.get("historyCommands").intValue();i++) { String actor=h.players().get(h.control().flow().playerIndex());h=accepted(historyRuntime.act(h,actor,"collect",Map.of("counter",RuleValue.entity("counter",actor)),h.version())); }
        int bytes=RulesStateCodec.encode(h).getBytes(StandardCharsets.UTF_8).length;boolean growth=bytes<=config.get("maximumCheckpointBytes").intValue(),timing=measures.stream().allMatch(m->(double)m.get("p95Ms")<=(double)m.get("limitMs"));
        var report=new LinkedHashMap<String,Object>();report.put("version",config.get("version").textValue());report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));report.put("architecture",System.getProperty("os.arch"));report.put("processors",Runtime.getRuntime().availableProcessors());report.put("fingerprint",compiled.definition().fingerprint());report.put("measurements",measures);report.put("historyCommands",config.get("historyCommands").intValue());report.put("historyEvents",h.events().size());report.put("checkpointBytes",bytes);report.put("wallClockEnforced",enforce);report.put("passed",growth&&(!enforce||timing));
        Path output=Path.of(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);System.out.println(JSON.writeValueAsString(report));
        if(!growth||enforce&&!timing)throw new IllegalStateException("Benchmark budget exceeded; see "+output);
    }
}
