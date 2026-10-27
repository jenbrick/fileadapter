package com.jenbrick.fileadapter.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.consent.ConsentSettings.MemberIdentity;
import com.jenbrick.fileadapter.source.SourceRecord;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Business assembly is independent of CSV, XML, JSON or fixed width parsing. */
@Component
public class ConsentRequestPlanner {
    private final MemberResolver resolver;
    private final ConsentPlanStore plans;
    private final ObjectMapper json;
    public ConsentRequestPlanner(MemberResolver resolver, ConsentPlanStore plans, ObjectMapper json) {
        this.resolver = resolver; this.plans = plans; this.json = json;
    }

    public List<ObjectNode> plan(String runId, SourceRecord source, ConsentSettings settings, Mode mode) {
        settings.validate();
        if (mode == Mode.LIVE && settings.lookup().mode() == ConsentSettings.LookupMode.FIXTURE) {
            throw new IllegalArgumentException("LIVE consent requires HTTP member resolution");
        }
        try {
            String saved = plans.get(runId, source.number());
            if (saved != null) {
                var result = new ArrayList<ObjectNode>();
                for (var request : json.readTree(saved)) result.add((ObjectNode) request);
                return result;
            }
            var result = assemble(source.payload(), settings);
            plans.save(runId, source.number(), json.writeValueAsString(result));
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Consent plan serialization failed", e);
        }
    }

    List<ObjectNode> assemble(JsonNode row, ConsentSettings settings) {
        // A missing preference column is a layout error. A present blank/null is an explicit no-op.
        for (String field : List.of("PROVIDER_CLINICAL_CONSENT_PREFERENCE", "PAYER_CONSENT_PREFERENCE")) {
            if (!row.has(field)) throw new IllegalArgumentException("Missing consent preference field");
        }
        String provider = status(row, "PROVIDER_CLINICAL_CONSENT_PREFERENCE", settings);
        String payer = status(row, "PAYER_CONSENT_PREFERENCE", settings);
        if (provider == null && payer == null) return List.of();
        String providerDate = provider == null ? null : date(row,
                "PROVIDER_CLINICAL_CONSENT_EFFECTIVE_DATE", settings, true);
        String payerDate = payer == null ? null : date(row,
                "PAYER_CONSENT_PREFERENCE_EFFECTIVE_DATE", settings, true);
        ObjectNode coverage = payer == null ? null : coverage(row, settings);
        var criteria = json.createObjectNode();
        for (String name : List.of("EXTERNAL_CONTRACT_ID", "RELATIONSHIP_CODE", "LAST_NAME", "FIRST_NAME",
                "MIDDLE_NAME", "GENDER_CODE", "DATE_OF_BIRTH", "EXTERNAL_GROUP_ID", "PAYER_ID", "PAYER_POLICY_ID")) {
            String value = text(row, name, List.of("EXTERNAL_CONTRACT_ID", "RELATIONSHIP_CODE", "LAST_NAME",
                    "FIRST_NAME", "DATE_OF_BIRTH").contains(name));
            if (value == null) criteria.putNull(name); else criteria.put(name, value);
        }
        criteria.put("DATE_OF_BIRTH", date(row, "DATE_OF_BIRTH", settings, true));
        criteria.put("providerTarget", settings.providerTarget());
        criteria.put("payerTarget", settings.payerTarget());
        criteria.put("resolveProvider", provider != null);
        criteria.put("resolvePayer", payer != null);
        var resolved = resolver.resolve(criteria, settings);
        if (resolved == null) throw new IllegalArgumentException("Member resolution is required");
        identity(resolved.owner()); identity(resolved.manager());
        String timestamp = settings.sourceDateTime();
        if (timestamp == null || timestamp.isBlank()) timestamp = OffsetDateTime.now(java.time.ZoneOffset.UTC).toString();
        var result = new ArrayList<ObjectNode>();
        if (provider != null) result.add(request(settings, resolved.owner(), resolved.manager(),
                resolved.providerConsentGuid(), settings.providerTarget(), provider, providerDate, timestamp, null));
        if (payer != null) result.add(request(settings, resolved.owner(), resolved.manager(),
                resolved.payerConsentGuid(), settings.payerTarget(), payer, payerDate, timestamp, coverage));
        return result;
    }
    private ObjectNode request(ConsentSettings settings, MemberIdentity owner, MemberIdentity manager,
            String guid, String target, String status, String effective, String timestamp, ObjectNode coverage) {
        var request = json.createObjectNode();
        if (guid == null || guid.isBlank()) request.putNull("consentGuid"); else request.put("consentGuid", guid.trim());
        request.put("consentTarget", target); request.put("consentStatus", status);
        request.set("consentOwner", json.valueToTree(owner));
        request.put("effectiveDate", effective); request.putNull("effectiveEndDate");
        var details = request.putObject("transactionDetails");
        details.put("sourceSystem", settings.sourceSystem()); details.put("sourceDateTime", timestamp);
        details.put("sourceTransactionIdentifier", settings.sourceTransactionIdentifier());
        details.set("consentManager", json.valueToTree(manager));
        if (coverage != null) request.set("coverage", coverage);
        return request;
    }
    private void identity(MemberIdentity value) {
        if (value == null || value.contract() == null || value.contract().isBlank()
                || value.contractMemberGuid() == null || value.contractMemberGuid().isBlank()
                || value.pin() == null || value.pin().codePointCount(0, value.pin().length()) != 3) {
            throw new IllegalArgumentException("Resolution requires contract, three-character pin and member GUID");
        }
    }
    private ObjectNode coverage(JsonNode row, ConsentSettings settings) {
        var coverage = json.createObjectNode();
        coverage.put("payerId", text(row, "PAYER_ID", true));
        coverage.put("otherPayerMemberId", text(row, "PAYER_POLICY_ID", true));
        String[][] optional = {{"groupNumber", "PAYER_GROUP_NUMBER"},
                {"policyHolderFirstName", "PAYER_SUBSCRIBER_FIRST_NAME"},
                {"policyHolderLastName", "PAYER_SUBSCRIBER_LAST_NAME"},
                {"policyHolderMiddleName", "PAYER_SUBSCRIBER_MIDDLE_NAME"},
                {"policyHolderGender", "PAYER_GENDER_CODE"}};
        for (var field : optional) {
            String value = text(row, field[1], false);
            if (value == null) coverage.putNull(field[0]); else coverage.put(field[0], value);
        }
        String dob = date(row, "PAYER_DATE_OF_BIRTH", settings, false);
        if (dob == null) coverage.putNull("policyHolderDateOfBirth"); else coverage.put("policyHolderDateOfBirth", dob);
        String relationship = settings.relationships().get(text(row, "RELATIONSHIP_CODE", true).toUpperCase(Locale.ROOT));
        if (relationship == null) throw new IllegalArgumentException("Unknown payer relationship code");
        coverage.put("relationshipToPolicyHolder", relationship);
        return coverage;
    }
    private String status(JsonNode row, String field, ConsentSettings settings) {
        String value = text(row, field, false);
        if (value == null) return null;
        String mapped = settings.preferences().get(value.toUpperCase(Locale.ROOT));
        if (mapped == null) throw new IllegalArgumentException("Unknown consent preference code");
        return mapped;
    }
    private String date(JsonNode row, String field, ConsentSettings settings, boolean required) {
        String value = text(row, field, required);
        if (value == null) return null;
        return LocalDate.parse(value, DateTimeFormatter.ofPattern(settings.datePattern(), Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT)).toString();
    }
    private String text(JsonNode row, String field, boolean required) {
        var node = row.get(field);
        if (node != null && !node.isNull() && !node.isTextual()) {
            throw new IllegalArgumentException("Consent fields must be strings");
        }
        String value = node == null || node.isNull() ? null : node.textValue().trim();
        if (value != null && value.isEmpty()) value = null;
        if (required && value == null) throw new IllegalArgumentException("Required consent field is missing");
        return value;
    }
}
