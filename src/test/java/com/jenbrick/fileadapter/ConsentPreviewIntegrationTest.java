package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.config.ProfileCatalog;
import com.jenbrick.fileadapter.consent.ConsentRequestPlanner;
import com.jenbrick.fileadapter.processing.FileProcessor;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import com.jenbrick.fileadapter.source.SourceRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("consent-demo")
@DirtiesContext
class ConsentPreviewIntegrationTest {
    static final Path WORK = temporary();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("ingestion.work-directory", WORK::toString);
    }
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired ObjectMapper json;
    @Autowired ProfileCatalog catalog;
    @Autowired ConsentRequestPlanner planner;

    @ParameterizedTest
    @ValueSource(strings={"consent.csv", "consent-noheader.csv", "consent-header.psv", "consent-noheader.psv",
            "consent.fw", "consent.xml", "consent-array.json", "consent.jsonl", "consent-object.json"})
    void formatsProduceEquivalentConsentBodiesWithOrigins(String file) throws Exception {
        String id = processor.process(Path.of("examples/consent", file), null, false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        var expected = Files.readAllLines(Path.of("examples/consent/expected-requests.jsonl"));
        var preview = json.readTree(WORK.resolve("preview").resolve(id).resolve(file+".preview.json").toFile());
        int count = file.contains("object") ? 2 : 6;
        assertThat(preview.get("requests")).hasSize(count);
        for (int i=0;i<count;i++) assertThat(preview.get("requests").get(i)).isEqualTo(json.readTree(expected.get(i)));
        var origins = json.readTree(Path.of("examples/consent/expected-request-details.json").toFile());
        for (int i=0;i<count;i++) assertThat(preview.get("requestDetails").get(i)).isEqualTo(origins.get(i));
        assertThat(preview.at("/requests/0/consentGuid").isNull()).isTrue();
        assertThat(preview.at("/requests/1/consentGuid").asText()).isEqualTo("SYN-CONSENT-20001");
        assertThat(preview.get("requests").get(0).has("coverage")).isFalse();
        assertThat(processor.process(Path.of("examples/consent", file), null, false)).isEqualTo(id);
    }

    @Test void allBlankPreferencesAreSuccessfulNoOpAndDoNotLookup() throws Exception {
        var rows=json.readTree(Path.of("examples/consent/consent-array.json").toFile());
        var row=rows.get(4).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)row).put("EXTERNAL_CONTRACT_ID","NO-MATCH");
        Path input=WORK.resolve("consent-object-noop.json");
        json.writeValue(input.toFile(),row);
        String id=processor.process(input,null,false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        assertThat(ledger.count(id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings={"unknown", "bad-date", "no-match", "missing-preference", "bad-relationship", "bad-payer"})
    void invalidLaterMemberFailsEntireFileWithValueFreeReport(String kind) throws Exception {
        var rows=json.readTree(Path.of("examples/consent/consent-array.json").toFile());
        var row=(com.fasterxml.jackson.databind.node.ObjectNode)rows.get(1);
        switch(kind) {
            case "unknown" -> row.put("PROVIDER_CLINICAL_CONSENT_PREFERENCE","SENSITIVE-UNKNOWN");
            case "bad-date" -> row.put("PROVIDER_CLINICAL_CONSENT_EFFECTIVE_DATE","2026-02-30");
            case "no-match" -> row.put("EXTERNAL_CONTRACT_ID","SENSITIVE-NO-MATCH");
            case "missing-preference" -> row.remove("PAYER_CONSENT_PREFERENCE");
            case "bad-relationship" -> {row.put("PAYER_CONSENT_PREFERENCE","GRANT");row.put("PAYER_CONSENT_PREFERENCE_EFFECTIVE_DATE","2026-01-01");row.put("RELATIONSHIP_CODE","INVALID");}
            default -> {row.put("PAYER_CONSENT_PREFERENCE","GRANT");row.put("PAYER_CONSENT_PREFERENCE_EFFECTIVE_DATE","2026-01-01");row.put("PAYER_ID","");}
        }
        Path input=WORK.resolve("consent-array-"+kind+".json");json.writeValue(input.toFile(),rows);
        String id=processor.process(input,null,false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(Files.exists(WORK.resolve("preview").resolve(id))).isFalse();
        assertThat(Files.readString(WORK.resolve("errors").resolve(id).resolve("error.json")))
                .doesNotContain("SENSITIVE", "2026-02-30");
    }

    @Test void frozenMemberPlanSurvivesStagingRecovery() throws Exception {
        String id=processor.process(Path.of("examples/consent/consent.csv"),null,false);
        var settings=catalog.resolve(Path.of("consent.csv"),null).consent();
        // If recovery calls lookup or reads the changed row instead of using the durable plan, it fails.
        var changed=json.createObjectNode().put("EXTERNAL_CONTRACT_ID","UNRESOLVABLE");
        var saved=planner.plan(id,new SourceRecord(1,changed),settings,
                com.jenbrick.fileadapter.config.IngestionProperties.Mode.PREVIEW);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).at("/consentOwner/contract").asText()).isEqualTo("990000001");
    }
    private static Path temporary() {
        try{return Files.createTempDirectory("consent-preview-");}
        catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
}
