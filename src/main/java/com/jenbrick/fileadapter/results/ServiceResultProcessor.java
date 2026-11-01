package com.jenbrick.fileadapter.results;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.delivery.UpsertClient.ServiceIssue;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ServiceResultProcessor {
    private final ResultStore results;
    private final ObjectMapper json;
    public ServiceResultProcessor(ResultStore results,ObjectMapper json){this.results=results;this.json=json;}
    public boolean apply(String id,ProcessingLedger.Request request,ErrorPolicy configured)throws IOException {
        if(request.responseIssues()==null)return false;
        var issues=json.readValue(request.responseIssues(),ServiceIssue[].class);
        var policy=configured==null?ErrorPolicy.defaults():configured;
        var origins=new LinkedHashSet<Long>();
        if(request.sourceRecords()==null)origins.add(request.sourceRecord());
        else for(var value:json.readTree(request.sourceRecords()))origins.add(value.longValue());
        var sources=results.sources(id).stream().filter(s->origins.contains(s.number())).toList();
        var findings=new ArrayList<ResultStore.Finding>();boolean hard=false;
        for(var issue:issues) {
            var text=policy.serviceCodes()==null?null:policy.serviceCodes().get(issue.code());
            if(text==null)throw new IOException("Unmapped scoped result code");
            var affected=sources.stream().filter(s->issue.scope()==ErrorPolicy.Scope.FILE
                    || (Objects.equals(s.contractId(),issue.contractId()) &&
                        (issue.scope()!=ErrorPolicy.Scope.MEMBER || Objects.equals(s.memberGuid(),issue.memberGuid()))))
                    .map(ResultStore.Source::number).toList();
            if(affected.isEmpty())throw new IOException("Unmatched scoped result identity");
            findings.add(new ResultStore.Finding(issue.severity().name(),issue.scope().name(),text.customerCode(),text.customerRemarks(),
                    text.internalRemarks(),affected,request.number()));
            hard|=issue.severity()==ErrorPolicy.Severity.HARD;
        }
        // Validate the whole response before recording any member outcome.
        for(var finding:findings)results.add(id,finding);
        return hard;
    }
}
