package com.metis.engine.core.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Developer compiler entry point; publication/session execution arrive in M3/M2. */
public final class RulesCompilerCli {
    private RulesCompilerCli() { }
    public static void main(String[] args) throws Exception {
        if(run(args,System.out)!=0)throw new IllegalArgumentException("Rules compilation failed; see diagnostics above");
    }
    public static int run(String[] args,PrintStream output) throws Exception {
        if(args.length!=1) { output.println("Usage: RulesCompilerCli <definition.json>");return 2; }
        var result=new RulesCompiler().compile(Files.readString(Path.of(args[0])));
        var report=new TreeMap<String,Object>();report.put("valid",result.valid());report.put("diagnostics",result.diagnostics());
        if(result.valid()) {
            var definition=result.definition();report.put("id",definition.id());report.put("revision",definition.revision());
            report.put("compilerVersion",definition.compilerVersion());report.put("fingerprint",definition.fingerprint());report.put("modules",definition.modules());
            var bounds=new TreeMap<String,Long>();definition.procedures().forEach((name,procedure)->bounds.put(name,procedure.instructionBound()));report.put("procedureInstructionBounds",bounds);
        }
        output.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));return result.valid()?0:2;
    }
}
