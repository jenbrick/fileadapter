package com.jenbrick.fileadapter.consent;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Settings for the optional member lookup; output rules belong to the projection. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = {"preferences", "relationships", "sourceSystem", "sourceTransactionIdentifier"})
public record ConsentSettings(String version, String providerTarget, String payerTarget,
        String datePattern,
        String sourceDateTime, Lookup lookup) {
    public enum LookupMode { FIXTURE, HTTP }
    public record Lookup(LookupMode mode, URI endpoint, Duration connectTimeout,
                         Duration readTimeout, List<Fixture> fixtures) {}
    public record Fixture(Map<String, String> match, Resolution result) {}
    public record MemberIdentity(String contract, String pin, String contractMemberGuid) {}
    public record Resolution(MemberIdentity owner, MemberIdentity manager,
                             String providerConsentGuid, String payerConsentGuid) {}

    public void validate() {
        required(version); required(providerTarget); required(payerTarget);
        if (providerTarget.equals(payerTarget)) throw new IllegalArgumentException("Consent targets must differ");
        required(datePattern);
        DateTimeFormatter.ofPattern(datePattern);
        if (sourceDateTime != null && !sourceDateTime.isBlank()) java.time.OffsetDateTime.parse(sourceDateTime);
        if (lookup == null || lookup.mode() == null) throw new IllegalArgumentException("Consent lookup is required");
        if (lookup.mode() == LookupMode.HTTP) {
            var url = lookup.endpoint();
            if (url == null || !List.of("http", "https").contains(url.getScheme()) || url.getHost() == null
                    || url.getUserInfo() != null || url.getFragment() != null || url.getQuery() != null
                    || lookup.connectTimeout() == null || lookup.connectTimeout().isZero() || lookup.connectTimeout().isNegative()
                    || lookup.readTimeout() == null || lookup.readTimeout().isZero() || lookup.readTimeout().isNegative()) {
                throw new IllegalArgumentException("Invalid consent lookup endpoint or timeouts");
            }
        } else if (lookup.fixtures() == null || lookup.fixtures().isEmpty()) {
            throw new IllegalArgumentException("Fixture lookup requires synthetic matches");
        }
    }
    private static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Consent setting is required");
    }
}
