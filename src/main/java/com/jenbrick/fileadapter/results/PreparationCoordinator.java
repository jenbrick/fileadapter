package com.jenbrick.fileadapter.results;

import com.fasterxml.jackson.databind.JsonNode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.mapping.JsonPaths;
import com.jenbrick.fileadapter.projection.Projection;
import com.jenbrick.fileadapter.projection.ProjectionEngine;
import com.jenbrick.fileadapter.source.*;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.jenbrick.fileadapter.results.ErrorPolicy.*;

/**
 * Check and enrich the rows, set aside rejected contracts when allowed, then build requests for the
 * contracts that remain.
 */
@Component
public class PreparationCoordinator {
    private final ResultStore results;
    private final SourceNormalizer normalizer;
    private final ProjectionEngine engine;
    public PreparationCoordinator(ResultStore results,SourceNormalizer normalizer,ProjectionEngine engine) {
        this.results=results;this.normalizer=normalizer;this.engine=engine;
    }
    public List<ProjectionEngine.Request> prepare(String id,List<SourceRecord> raw,Profile profile,Mode mode) {
        var policy=profile.errors()==null?ErrorPolicy.defaults():profile.errors();
        var normalized=new ArrayList<SourceRecord>();
        var rejected=new LinkedHashSet<String>();
        boolean hard=false;
        // Normalize and check rules for the saved source rows before making lookups.
        for(var source:raw) {
            var row=normalizer.normalize(source,profile.layout());normalized.add(row);
            String key=value(row.payload(),policy.contractKey());
            results.identify(id,row.number(),key,null,null);
            if(policy.rules()!=null) for(var rule:policy.rules()) {
                var v=JsonPaths.get(row.payload(),rule.source());
                boolean matches=rule.condition()==Condition.MISSING ? !present(v)
                        : !v.isMissingNode() && !v.isNull() && v.asText().equals(rule.equalsValue());
                if(!matches) continue;
                Severity severity=rule.severity();
                // Without a reliable contract key, we cannot safely isolate just one contract.
                if(severity==Severity.MEDIUM && key==null) severity=Severity.HARD;
                results.add(id,new ResultStore.Finding(severity.name(),severity==Severity.MEDIUM?"CONTRACT":severity==Severity.HARD?"FILE":"RECORD",
                        rule.code(),rule.customerRemarks(),rule.internalRemarks(),List.of(row.number()),null));
                if(severity==Severity.MEDIUM) rejected.add(key);
                if(severity==Severity.HARD) hard=true;
            }
        }
        // Stop before any requests are built or delivered when a HARD rule rejects the file.
        if(hard) throw new RecordFailure("Hard configured validation failure");
        var enriched=new ArrayList<SourceRecord>();
        for(var row:normalized) {
            String key=value(row.payload(),policy.contractKey());
            if(rejected.contains(key)) continue;
            try {
                // The configured chain resolves group context first, then member demographics.
                var item=engine.enrich(id,row,profile,mode);
                results.identify(id,row.number(),key,value(item.payload(),policy.resolvedContractKey()),value(item.payload(),policy.resolvedMemberKey()));
                enriched.add(item);
            } catch(RuntimeException e) {
                if(policy.enrichmentSeverity()!=Severity.MEDIUM || key==null) throw e;
                rejected.add(key);
                results.add(id,new ResultStore.Finding("MEDIUM","CONTRACT","LOOKUP_FAILED","Contract could not be processed.",
                        "Enrichment failed ("+e.getClass().getSimpleName()+")",List.of(row.number()),null));
            }
        }
        // Remove all rows of a rejected external contract, including rows enriched earlier.
        var viable=enriched.stream().filter(r->!rejected.contains(value(r.payload(),policy.contractKey()))).toList();
        for(String key:rejected) rejectKey(id,key,"CONTRACT_FAILED","Contract could not be processed.");
        // Check each complete contract separately when the profile allows the other contracts to
        // continue.
        if(policy.projectionSeverity()==Severity.MEDIUM) {
            var batch=profile.projection().batching();
            var groups=new LinkedHashMap<List<JsonNode>,List<SourceRecord>>();
            for(var row:viable) {
                if(!selected(row.payload(),batch.where())) continue;
                var key=new ArrayList<JsonNode>();
                for(String path:batch.groupBy()) {
                    var v=JsonPaths.get(row.payload(),path);
                    if(!present(v) || v.isContainerNode()) throw new RecordFailure("Missing contract grouping identity");
                    key.add(v);
                }
                groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);
            }
            var accepted=new ArrayList<SourceRecord>();
            var p=profile.projection();
            var single=new Projection(p.version(),p.scope(),p.maxRecords(),p.enrichers(),p.inputContract(),p.skipWhenEmpty(),p.root(),
                    new Projection.Batching(batch.groupBy(),1,batch.maxRecordsPerGroup(),batch.memberKey(),batch.maxMembersPerGroup(),batch.where()));
            for(var group:groups.values()) try {
                // Use the same mapping and limits for this check, but give it one contract at a time.
                engine.project(group,single);accepted.addAll(group);
            } catch(RecordFailure e) {
                var sourceKeys=new LinkedHashSet<String>();
                for(var row:group) {
                    String key=value(row.payload(),policy.contractKey());
                    if(key==null)throw new RecordFailure("Missing source contract identity for isolation");
                    sourceKeys.add(key);
                }
                var origins=results.sources(id).stream().filter(row->sourceKeys.contains(row.contractKey())).map(ResultStore.Source::number).toList();
                results.add(id,new ResultStore.Finding("MEDIUM","CONTRACT","CONTRACT_FAILED","Contract could not be processed.",
                        e.getMessage(),origins,null));
            }
            viable=accepted;
        }
        // Only the remaining contracts are packed into the final request bodies.
        return engine.project(viable,profile.projection());
    }
    private void rejectKey(String id,String key,String code,String text) {
        var rows=results.sources(id).stream().filter(s->Objects.equals(s.contractKey(),key)).map(ResultStore.Source::number).toList();
        results.add(id,new ResultStore.Finding("MEDIUM","CONTRACT",code,text,"All source rows in the rejected contract were withheld.",rows,null));
    }
    private static boolean present(JsonNode v) {return v!=null && !v.isMissingNode() && !v.isNull() && (!v.isTextual() || !v.asText().isBlank());}
    private static String value(JsonNode row,String path) {if(path==null)return null;var v=JsonPaths.get(row,path);return present(v)&&!v.isContainerNode()?v.asText():null;}
    private static boolean selected(JsonNode row,Projection.Where where) {
        if(where==null)return true;
        if(where.anyPresent()!=null)return where.anyPresent().stream().anyMatch(p->present(JsonPaths.get(row,p)));
        var v=JsonPaths.get(row,where.source());return where.present()!=null?present(v)==where.present():present(v)&&v.asText().equals(where.equalsValue());
    }
}
