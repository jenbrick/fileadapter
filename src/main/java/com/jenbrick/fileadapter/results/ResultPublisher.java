package com.jenbrick.fileadapter.results;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.processing.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Write separate customer and IT result files, then publish ready manifests for the outside process
 * that sends them.
 */
@Component
public class ResultPublisher {
    private final ResultStore store;
    private final SourceResultWriter writer;
    private final ObjectMapper json;
    private final Path customer,internal;
    public ResultPublisher(ResultStore store,SourceResultWriter writer,ObjectMapper json,
            com.jenbrick.fileadapter.config.IngestionProperties properties,
            @Value("${results.customer-directory:}") String customerDirectory,
            @Value("${results.internal-directory:}") String internalDirectory) {
        this.store=store;this.writer=writer;this.json=json;
        customer=customerDirectory.isBlank()?properties.workDirectory().resolve("results/customer"):Path.of(customerDirectory);
        internal=internalDirectory.isBlank()?properties.workDirectory().resolve("results/internal"):Path.of(internalDirectory);
    }
    public void publish(ProcessingLedger.Run run,RunSnapshot snapshot)throws IOException {
        var policy=snapshot.profile().errors()==null?ErrorPolicy.defaults():snapshot.profile().errors();
        var findings=store.findings(run.id());var sources=store.sources(run.id());
        var selected=new ArrayList<SourceResultWriter.Row>();
        for(var row:sources) {
            // Return a source row once. The highest severity wins, with code ordering to make ties
            // predictable.
            var matches=findings.stream().filter(f->f.sourceRecords().contains(row.number()) && (!f.severity().equals("SOFT") || policy.includeWarnings()))
                    .sorted(Comparator.comparingInt((ResultStore.Finding f)->rank(f.severity())).thenComparing(ResultStore.Finding::code)).toList();
            if(!matches.isEmpty()) {var f=matches.get(0);selected.add(new SourceResultWriter.Row(row,f.code(),f.customerRemarks()));}
            else if(policy.includeSuccess() && (run.status().startsWith("COMPLETED") || run.status().startsWith("PREVIEWED")))
                selected.add(new SourceResultWriter.Row(row,"OK",snapshot.mode().name().equals("PREVIEW")?"Preview only; not delivered.":"Processed."));
        }
        // IT gets every finding and the overall run state, including warnings that never go to the
        // customer.
        var diagnostic=json.createObjectNode();diagnostic.put("runId",run.id());diagnostic.put("inputFile",run.filename());
        diagnostic.put("status",run.status());diagnostic.put("mode",snapshot.mode().name());diagnostic.put("completeSource",store.completeInput(run.id()));
        diagnostic.put("sourceRecordCount",sources.size());diagnostic.put("customerRecordCount",selected.size());
        diagnostic.set("findings",json.valueToTree(findings));
        diagnostic.put("hardCount",findings.stream().filter(f->f.severity().equals("HARD")).count());
        diagnostic.put("mediumCount",findings.stream().filter(f->f.severity().equals("MEDIUM")).count());
        diagnostic.put("softCount",findings.stream().filter(f->f.severity().equals("SOFT")).count());
        // Use a stable revision name so repeated publication of the same result can be recognized.
        String state=json.writeValueAsString(diagnostic);
        String revision;
        try {revision=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8))).substring(0,16);}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        Path cdir=customer.resolve(run.id()),idir=internal.resolve(run.id());Files.createDirectories(cdir);Files.createDirectories(idir);
        String filename=run.filename().replaceAll("[^A-Za-z0-9._-]","_");if(filename.length()>100)filename=filename.substring(0,100);
        int dot=filename.lastIndexOf('.');String stem=dot>0?filename.substring(0,dot):filename;
        if(stem.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))stem="source-"+stem;
        String extension=dot>0?filename.substring(dot):switch(snapshot.profile().layout().format()){case XML->".xml";case JSON->".json";case FIXED_WIDTH->".fw";default->".csv";};
        String customerFile=null;
        if(!selected.isEmpty()) {
            customerFile=stem+".results."+revision+extension;
            // Write the source-format customer file before pointing an outside process at it.
            Path pending=cdir.resolve(customerFile+".tmp");
            try {writer.write(pending,snapshot.profile().layout(),policy,selected);move(pending,cdir.resolve(customerFile));}
            catch(IOException|RuntimeException e) {
                Files.deleteIfExists(pending);
                store.add(run.id(),new ResultStore.Finding("HARD","FILE","RESULT_EXPORT","Result export requires support.",
                        "Customer result generation failed ("+e.getClass().getSimpleName()+")",selected.stream().map(r->r.source().number()).toList(),null));
                diagnostic.set("findings",json.valueToTree(store.findings(run.id())));
                diagnostic.put("hardCount",store.findings(run.id()).stream().filter(f->f.severity().equals("HARD")).count());
                diagnostic.put("customerExportError",e.getClass().getSimpleName());diagnostic.put("customerReady",false);
                customerFile=null;
            }
        }
        // Do not publish a customer-ready result while input is incomplete or the run is paused.
        diagnostic.put("customerFile",customerFile);diagnostic.put("customerReady",customerFile!=null && store.completeInput(run.id()) && !run.status().equals("PAUSED"));
        String internalFile="diagnostics."+revision+".json";
        atomicJson(idir.resolve(internalFile),diagnostic);
        var manifest=json.createObjectNode().put("runId",run.id()).put("revision",revision).put("status",run.status()).put("mode",snapshot.mode().name())
                .put("completeSource",store.completeInput(run.id())).put("internalFile",internalFile).put("customerFile",customerFile)
                .put("customerReady",diagnostic.path("customerReady").asBoolean());
        // The manifest is the handoff. Consumers should not pick up temporary or unfinished files.
        atomicJson(idir.resolve("ready.json"),manifest);
        if(manifest.path("customerReady").asBoolean())atomicJson(cdir.resolve("ready.json"),manifest);
        else Files.deleteIfExists(cdir.resolve("ready.json"));
        if(diagnostic.has("customerExportError"))throw new IOException("Customer result export failed; inspect internal diagnostic");
    }
    // An unreadable or unmatched input still needs an IT report, even when no customer record can
    // be built.
    public void intakeRejected(Path originalCopy,String filename,Exception error)throws IOException {
        Path directory=internal.resolve("intake-"+java.util.UUID.randomUUID());Files.createDirectories(directory);
        var report=json.createObjectNode().put("status","FAILED").put("severity","HARD").put("scope","FILE")
                .put("code","INTAKE_REJECTED").put("inputFile",filename).put("originalCopy",originalCopy.toString())
                .put("internalRemarks","Input rejected before profile selection ("+error.getClass().getSimpleName()+")")
                .put("customerReady",false).put("completeSource",false);
        atomicJson(directory.resolve("diagnostics.json"),report);
        atomicJson(directory.resolve("ready.json"),json.createObjectNode().put("internalFile","diagnostics.json").put("customerReady",false));
    }
    private int rank(String severity){return severity.equals("HARD")?0:severity.equals("MEDIUM")?1:2;}
    private void atomicJson(Path file,Object data)throws IOException {
        Path pending=file.resolveSibling(file.getFileName()+".tmp");json.writerWithDefaultPrettyPrinter().writeValue(pending.toFile(),data);move(pending,file);
    }
    private void move(Path from,Path to)throws IOException {
        try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
    }
}
