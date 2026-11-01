package com.jenbrick.fileadapter.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.*;
import com.jenbrick.fileadapter.consent.*;
import com.jenbrick.fileadapter.mapping.JsonPaths;
import com.jenbrick.fileadapter.source.SourceRecord;
import org.springframework.stereotype.Component;

@Component
public class GroupEnricher implements RecordEnricher {
    private final GroupResolver resolver;
    private final GroupContextStore store;
    private final ObjectMapper json;
    public GroupEnricher(GroupResolver resolver,GroupContextStore store,ObjectMapper json){this.resolver=resolver;this.store=store;this.json=json;}
    @Override public String name(){return "group-context";}
    @Override public void validate(Profile profile){if(profile.group()==null)throw new IllegalArgumentException("group-context requires group settings");profile.group().validate();}
    @Override public SourceRecord enrich(SourceRecord row,Profile profile,IngestionProperties.Mode mode){return enrichForRun(null,row,profile,mode);}
    @Override public SourceRecord enrichForRun(String runId,SourceRecord source,Profile profile,IngestionProperties.Mode mode) {
        var settings=profile.group();if(settings==null)throw new IllegalArgumentException("Missing group settings");
        if(mode==IngestionProperties.Mode.LIVE && settings.lookup().mode()==ConsentSettings.LookupMode.FIXTURE)
            throw new IllegalArgumentException("LIVE refuses fixture group lookup");
        if(!(source.payload() instanceof ObjectNode row) || row.has("resolvedGroup"))throw new IllegalArgumentException("Reserved resolvedGroup namespace");
        if(!present(row.get("PROVIDER_CLINICAL_CONSENT_PREFERENCE")) && !present(row.get("PAYER_CONSENT_PREFERENCE")))return source;
        var id=JsonPaths.get(row,settings.sourceField());
        if(!id.isTextual() || id.asText().isBlank())throw new IllegalArgumentException("Required external group ID is missing");
        String external=id.asText().trim();
        var context=runId==null?null:store.get(runId,external);
        if(context==null) {
            context=GroupSettings.check(resolver.resolve(external,settings));
            if(runId!=null)store.save(runId,external,context);
        }
        var enriched=row.deepCopy();enriched.set("resolvedGroup",json.valueToTree(context));
        return new SourceRecord(source.number(),enriched);
    }
    private boolean present(com.fasterxml.jackson.databind.JsonNode value){return value!=null && !value.isNull() && !value.asText().isBlank();}
}
