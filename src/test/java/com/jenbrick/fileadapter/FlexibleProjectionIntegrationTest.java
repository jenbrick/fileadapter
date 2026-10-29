package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.ProfileCatalog;
import com.jenbrick.fileadapter.processing.FileProcessor;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import com.jenbrick.fileadapter.projection.Projection;
import com.jenbrick.fileadapter.projection.ProjectionEngine;
import com.jenbrick.fileadapter.source.SourceRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("flexible-demo")
@DirtiesContext
class FlexibleProjectionIntegrationTest {
    static final Path WORK=temp();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("ingestion.work-directory",WORK::toString);}
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired ObjectMapper json;
    @Autowired ProfileCatalog profiles;
    @Autowired ProjectionEngine engine;

    @ParameterizedTest @CsvSource({"csv,flexible-csv-v1","psv,flexible-pipe-v1","xml,flexible-xml-v1","json,flexible-json-v1","fw,flexible-fixed-v1"})
    void allAdaptersReproduceTheUserSample(String extension,String profile)throws Exception{
        String file="flexible-consents."+extension;
        String id=processor.process(Path.of("examples/flexible",file),profile,false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        assertThat(ledger.count(id)).isEqualTo(1);
        var body=json.readTree(ledger.next(id,0).payload());
        assertThat(body).isEqualTo(json.readTree(Path.of("examples/flexible/expected-envelope.json").toFile()));
        assertThat(body.at("/consents")).hasSize(3);
        assertThat(body.at("/consents/2/coverage")).hasSize(2);
        assertThat(body.at("/consents/0/coverage")).isEmpty();
        assertThat(body.at("/consents/1/transactionDetails/sourceSystem").asText()).isEqualTo("XYZ");
        assertThat(body.at("/consents/1/consentManager/pin").asText()).isEqualTo("002");
        assertThat(body.at("/consents/2/coverage/0/policyHolderMiddleName").isNull()).isTrue();
        var preview=json.readTree(WORK.resolve("preview").resolve(id).resolve(file+".preview.json").toFile());
        assertThat(preview.at("/source/engineVersion").intValue()).isEqualTo(4);
        assertThat(preview.at("/source/requestBodyShape").asText()).isEqualTo("CONFIGURED_PROJECTION");
        assertThat(preview.at("/requestDetails/0/sourceRecordNumbers").toString()).isEqualTo("[1,2,3]");
        assertThat(processor.process(Path.of("examples/flexible",file),profile,false)).isEqualTo(id);
    }
    @ParameterizedTest @CsvSource({"flexible-root-array-v1,expected-root-array.json","flexible-reshaped-v1,expected-reshaped.json"})
    void changingOnlyConfigurationChangesTheJsonShape(String profile,String expected)throws Exception{
        String id=processor.process(Path.of("examples/flexible/flexible-consents.csv"),profile,false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        assertThat(json.readTree(ledger.next(id,0).payload())).isEqualTo(json.readTree(Path.of("examples/flexible",expected).toFile()));
    }
    @Test void recordScopeAndNoEnrichmentAreDomainIndependent()throws Exception{
        String id=processor.process(Path.of("examples/flexible/flexible-consents.csv"),"flexible-record-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        assertThat(ledger.count(id)).isEqualTo(3);
        assertThat(json.readTree(ledger.next(id,0).payload()).at("/person/firstName").asText()).isEqualTo("Alex");
    }
    @Test void repeatedIdenticalRowsDoNotDuplicateConsentOrCoverage()throws Exception{
        var rows=json.readTree(Path.of("examples/flexible/flexible-consents.json").toFile());
        ((com.fasterxml.jackson.databind.node.ArrayNode)rows).add(rows.get(2).deepCopy());
        String id=processJson("duplicate",rows);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        assertThat(json.readTree(ledger.next(id,0).payload())).isEqualTo(json.readTree(Path.of("examples/flexible/expected-envelope.json").toFile()));
        assertThat(ledger.next(id,0).sourceRecords()).isEqualTo("[1, 2, 3, 4]");
    }
    @Test void incompatibleGroupedConsentValuesFailWithoutLeakingThem()throws Exception{
        var rows=json.readTree(Path.of("examples/flexible/flexible-consents.json").toFile());
        ((ObjectNode)rows.get(2)).put("PROVIDER_CLINICAL_CONSENT_PREFERENCE","RVOKE");
        String id=processJson("conflict",rows);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        String error=Files.readString(WORK.resolve("errors").resolve(id).resolve("error.json"));
        assertThat(error).contains("Conflicting grouped values").doesNotContain("RVOKE","John","Smith");
        assertThat(ledger.count(id)).isZero();
    }
    @Test void conflictingDuplicateCoverageFailsRatherThanTakingTheFirstRow()throws Exception{
        var rows=json.readTree(Path.of("examples/flexible/flexible-consents.json").toFile());
        var last=(ObjectNode)rows.get(2);var first=rows.get(1);
        for(String field:List.of("PAYER_ID","PAYER_POLICY_ID","PAYER_GROUP_NUMBER")) last.set(field,first.get(field));
        last.put("PAYER_SUBSCRIBER_FIRST_NAME","DIFFERENT-SENSITIVE-VALUE");
        String id=processJson("coverage-conflict",rows);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(ledger.count(id)).isZero();
    }
    @Test void emptyCollectionsSkipThePostAndMissingRequiredColumnsFail()throws Exception{
        var rows=json.readTree(Path.of("examples/flexible/flexible-consents.json").toFile());
        for(var row:rows) {((ObjectNode)row).put("PROVIDER_CLINICAL_CONSENT_PREFERENCE","");((ObjectNode)row).put("PAYER_CONSENT_PREFERENCE","");}
        String id=processJson("noop",rows);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");assertThat(ledger.count(id)).isZero();
        ((ObjectNode)rows.get(0)).remove("PAYER_CONSENT_PREFERENCE");
        String failed=processJson("missing-column",rows);
        assertThat(ledger.get(failed).status()).isEqualTo("FAILED");
    }
    @Test void projectionLimitsAndInvalidDefinitionsAreRejected()throws Exception{
        var base=profiles.resolve(Path.of("flexible.csv"),null).projection();
        var config=(ObjectNode)json.valueToTree(base);
        config.put("maxRecords",2);
        var limited=json.treeToValue(config,Projection.class);
        assertThatThrownBy(()->engine.project(List.of(new SourceRecord(1,json.createObjectNode()),new SourceRecord(2,json.createObjectNode()),new SourceRecord(3,json.createObjectNode())),limited))
                .hasMessageContaining("limit");
        config=(ObjectNode)json.valueToTree(base);((ObjectNode)config.get("root")).put("source","OTHER");
        var ambiguous=json.treeToValue(config,Projection.class);
        assertThatThrownBy(()->engine.validate(ambiguous)).hasMessageContaining("exactly one");
        config=(ObjectNode)json.valueToTree(base);config.putArray("enrichers").add("not-registered");
        var unknown=json.treeToValue(config,Projection.class);
        assertThatThrownBy(()->engine.validate(unknown)).hasMessageContaining("enricher");
    }
    @Test void frozenEnrichmentIsReusedEvenIfTheOriginalRowCannotResolve()throws Exception{
        var profile=profiles.resolve(Path.of("flexible.csv"),null);
        String id=processor.process(Path.of("examples/flexible/flexible-consents.csv"),profile.id(),false);
        var row=json.createObjectNode().put("PROVIDER_CLINICAL_CONSENT_PREFERENCE","GRANT").put("PAYER_CONSENT_PREFERENCE","");
        var saved=engine.enrich(id,new SourceRecord(1,row),profile,com.jenbrick.fileadapter.config.IngestionProperties.Mode.PREVIEW);
        assertThat(saved.payload().path("resolved").has("providerConsentGuid")).isTrue();
        assertThat(saved.payload().at("/resolved/owner/contractMemberGuid").asText()).isEqualTo("MEMBER-GUID-001");
    }
    String processJson(String name,com.fasterxml.jackson.databind.JsonNode rows)throws Exception{
        Path path=WORK.resolve(name+".json");json.writeValue(path.toFile(),rows);
        return processor.process(path,"flexible-json-v1",false);
    }
    static Path temp(){try{return Files.createTempDirectory("flexible-projection-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
