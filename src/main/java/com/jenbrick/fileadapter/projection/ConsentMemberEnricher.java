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
import org.springframework.stereotype.Component;

/**
 * Use the resolved group context to look up the member, then add the internal identities needed by
 * the JSON mapping.
 */
@Component
public class ConsentMemberEnricher implements RecordEnricher {
    private final MemberResolver resolver;
    private final ObjectMapper json;
    public ConsentMemberEnricher(MemberResolver resolver, ObjectMapper json) {this.resolver=resolver;this.json=json;}
    @Override public String name() {return "consent-member";}
    @Override public void validate(Profile profile) {
        if(profile.consent()==null) throw new IllegalArgumentException("consent-member needs lookup settings");
        profile.consent().validate();
        // Require group lookup first. Member matching needs the internal group number and customer ID.
        var names=profile.projection().enrichers();
        if(names==null || names.indexOf("group-context")<0 || names.indexOf("group-context")>=names.indexOf(name()))
            throw new IllegalArgumentException("group-context must precede consent-member");
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
        // A row with no consent changes does not need member lookup.
        if(!provider && !payer) return source;
        // Read the group context added by the prior enricher, rather than trusting internal IDs in
        // the input.
        var group=row.path("resolvedGroup");
        String groupNumber=requiredText(group,"internalGroupNumber"),customerId=requiredText(group,"customerId");
        // Build only the allowed member fields. Payer details and consent preferences stay out of
        // this request.
        var member=new com.jenbrick.fileadapter.consent.MemberLookupPayload(
                requiredText(row,"EXTERNAL_CONTRACT_ID"),requiredText(row,"RELATIONSHIP_CODE"),
                requiredText(row,"FIRST_NAME"),optionalText(row,"MIDDLE_NAME"),requiredText(row,"LAST_NAME"),
                optionalText(row,"GENDER_CODE"),LocalDate.parse(requiredText(row,"DATE_OF_BIRTH"),
                    DateTimeFormatter.ofPattern(settings.datePattern()).withResolverStyle(ResolverStyle.STRICT)).toString(),
                groupNumber,customerId);
        // The service adapter returns identities; the projection will decide where to put them in JSON.
        var result=resolver.resolve(member,settings);
        if(result==null) throw new IllegalArgumentException("Unresolved member");
        check(result.owner());check(result.manager());
        // Add internal values to a copy so the saved original record remains suitable for a
        // customer return.
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
    private static String optionalText(JsonNode row,String field) {
        var value=row.get(field);
        if(value==null || value.isNull())return null;
        if(!value.isTextual())throw new IllegalArgumentException("Member lookup fields must be strings");
        return value.asText().trim();
    }
    private static String requiredText(JsonNode row,String field) {
        String text=optionalText(row,field);
        if(text==null || text.isBlank())throw new IllegalArgumentException("Required member/group field is missing: "+field);
        return text;
    }
    private static boolean present(JsonNode value) {return value!=null && !value.isNull() && !value.asText().isBlank();}
    private static void check(ConsentSettings.MemberIdentity value) {
        if(value==null || value.contract()==null || value.contract().isBlank()
                || value.contractMemberGuid()==null || value.contractMemberGuid().isBlank()
                || value.pin()==null || value.pin().codePointCount(0,value.pin().length())!=3)
            throw new IllegalArgumentException("Invalid resolved identity");
    }
}
