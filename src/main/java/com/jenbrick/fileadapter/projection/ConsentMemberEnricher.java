package com.jenbrick.fileadapter.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.consent.ConsentSettings;
import com.jenbrick.fileadapter.consent.MemberResolver;
import com.jenbrick.fileadapter.source.SourceRecord;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import org.springframework.stereotype.Component;

/** Optional example business plugin. It enriches values; templates determine every output path. */
@Component
public class ConsentMemberEnricher implements RecordEnricher {
    private final MemberResolver resolver;
    private final ObjectMapper json;
    public ConsentMemberEnricher(MemberResolver resolver, ObjectMapper json) {this.resolver=resolver;this.json=json;}
    @Override public String name() {return "consent-member";}
    @Override public void validate(Profile profile) {
        if(profile.consent()==null) throw new IllegalArgumentException("consent-member needs lookup settings");
        profile.consent().validate();
    }
    @Override public SourceRecord enrich(SourceRecord source, Profile profile, Mode mode) {
        var settings=profile.consent();
        if(settings==null) throw new IllegalArgumentException("consent-member needs lookup settings");
        if(mode==Mode.LIVE && settings.lookup().mode()==ConsentSettings.LookupMode.FIXTURE)
            throw new IllegalArgumentException("LIVE refuses fixture enrichment");
        if(!(source.payload() instanceof ObjectNode row) || row.has("resolved"))
            throw new IllegalArgumentException("Enrichment requires an object without reserved resolved field");
        boolean provider=present(row.get("PROVIDER_CLINICAL_CONSENT_PREFERENCE"));
        boolean payer=present(row.get("PAYER_CONSENT_PREFERENCE"));
        if(!provider && !payer) return source;
        var criteria=json.createObjectNode();
        for(String field:List.of("EXTERNAL_CONTRACT_ID","RELATIONSHIP_CODE","LAST_NAME","FIRST_NAME",
                "MIDDLE_NAME","GENDER_CODE","DATE_OF_BIRTH","EXTERNAL_GROUP_ID","PAYER_ID","PAYER_POLICY_ID")) {
            var value=row.get(field);
            if(value!=null && !value.isNull() && !value.isTextual()) throw new IllegalArgumentException("Lookup fields must be strings");
            if(value==null || value.isNull()) criteria.putNull(field); else criteria.put(field,value.asText().trim());
        }
        for(String field:List.of("EXTERNAL_CONTRACT_ID","RELATIONSHIP_CODE","LAST_NAME","FIRST_NAME","DATE_OF_BIRTH"))
            if(!present(criteria.get(field))) throw new IllegalArgumentException("Required lookup field is missing");
        criteria.put("DATE_OF_BIRTH",LocalDate.parse(criteria.path("DATE_OF_BIRTH").asText(),
                DateTimeFormatter.ofPattern(settings.datePattern()).withResolverStyle(ResolverStyle.STRICT)).toString());
        criteria.put("providerTarget",settings.providerTarget());criteria.put("payerTarget",settings.payerTarget());
        criteria.put("resolveProvider",provider);criteria.put("resolvePayer",payer);
        var result=resolver.resolve(criteria,settings);
        if(result==null) throw new IllegalArgumentException("Unresolved member");
        check(result.owner());check(result.manager());
        ObjectNode enriched=row.deepCopy();
        ObjectNode resolved=json.valueToTree(result);
        // Nullable lookup fields are part of this plugin's canonical source contract.
        resolved.put("providerConsentGuid",result.providerConsentGuid());
        resolved.put("payerConsentGuid",result.payerConsentGuid());
        resolved.put("sourceDateTime",settings.sourceDateTime()==null || settings.sourceDateTime().isBlank()
                ? OffsetDateTime.now(java.time.ZoneOffset.UTC).toString():settings.sourceDateTime());
        enriched.set("resolved",resolved);
        return new SourceRecord(source.number(),enriched);
    }
    private static boolean present(JsonNode value) {return value!=null && !value.isNull() && !value.asText().isBlank();}
    private static void check(ConsentSettings.MemberIdentity value) {
        if(value==null || value.contract()==null || value.contract().isBlank()
                || value.contractMemberGuid()==null || value.contractMemberGuid().isBlank()
                || value.pin()==null || value.pin().codePointCount(0,value.pin().length())!=3)
            throw new IllegalArgumentException("Invalid resolved identity");
    }
}
