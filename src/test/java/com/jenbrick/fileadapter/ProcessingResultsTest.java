package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.*;
import com.jenbrick.fileadapter.processing.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
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
class ProcessingResultsTest {
    static final Path WORK=temp();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("ingestion.work-directory",WORK::toString);}
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired ObjectMapper json;
    @Test void mediumWithholdsOnlyTheContractAndWritesOriginalRowsPlusCodeAndRemarks()throws Exception {
        String id=processor.process(Path.of("examples/flexible/contract-batches.csv"),"error-demo-medium-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED_WITH_ERRORS");assertThat(ledger.count(id)).isEqualTo(1);
        var body=json.readTree(ledger.next(id,0).payload());assertThat(body).hasSize(2);
        assertThat(body.get(0).path("contractId").asText()).isEqualTo("222222222");
        var manifest=manifest("customer",id);String actual=Files.readString(WORK.resolve("results/customer").resolve(id).resolve(manifest.path("customerFile").asText())).replace("\r\n","\n");
        assertThat(actual).isEqualTo(Files.readString(Path.of("examples/flexible/expected-customer-medium.csv")));
        var report=report(id);assertThat(report.path("softCount").asInt()).isEqualTo(2);assertThat(report.path("mediumCount").asInt()).isGreaterThan(0);
        var before=Files.readString(WORK.resolve("results/internal").resolve(id).resolve("ready.json"));
        assertThat(processor.resume(id)).isTrue();
        assertThat(Files.readString(WORK.resolve("results/internal").resolve(id).resolve("ready.json"))).isEqualTo(before);
    }
    @Test void hardBlocksAllRequestsAndReturnsEveryParsedRecord()throws Exception {
        String id=processor.process(Path.of("examples/flexible/contract-batches.csv"),"error-demo-hard-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");assertThat(ledger.count(id)).isZero();
        var m=manifest("customer",id);String returned=Files.readString(WORK.resolve("results/customer").resolve(id).resolve(m.path("customerFile").asText())).replace("\r\n","\n");
        assertThat(returned).isEqualTo(Files.readString(Path.of("examples/flexible/expected-customer-hard.csv")));
        assertThat(report(id).path("hardCount").asInt()).isGreaterThan(0);
    }
    @Test void softWarnsOnlyAndCanOptionallyAppearInCustomerResults()throws Exception {
        String id=processor.process(Path.of("examples/flexible/contract-batches.csv"),"error-demo-soft-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");assertThat(ledger.count(id)).isEqualTo(2);
        var m=manifest("customer",id);String returned=Files.readString(WORK.resolve("results/customer").resolve(id).resolve(m.path("customerFile").asText())).replace("\r\n","\n");
        assertThat(returned).isEqualTo(Files.readString(Path.of("examples/flexible/expected-customer-soft.csv")));
        assertThat(report(id).path("softCount").asInt()).isEqualTo(2);
    }
    @Test void enrichmentErrorRejectsOnlyThatContract()throws Exception {
        var rows=Files.readAllLines(Path.of("examples/flexible/contract-batches.csv"));
        rows.set(3,rows.get(3).replace("1980-01-01","INVALID-DOB"));
        Path path=WORK.resolve("lookup-errors.csv");Files.write(path,rows);
        String id=processor.process(path,"error-demo-service-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED_WITH_ERRORS");
        assertThat(json.readTree(ledger.next(id,0).payload())).hasSize(2);
    }
    @Test void malformedInputDoesNotPublishAnIncompleteCustomerReturnAsReady()throws Exception {
        Path path=WORK.resolve("malformed.csv");Files.writeString(path,"FIRST_NAME,LAST_NAME\nAlex,Example\nwrong,column,count\n");
        String id=processor.process(path,"flexible-csv-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(Files.exists(WORK.resolve("results/customer").resolve(id).resolve("ready.json"))).isFalse();
        assertThat(report(id).path("completeSource").asBoolean()).isFalse();
    }
    @Test void projectionConflictCanRejectAContractWithoutFailingTheOtherContracts()throws Exception {
        var rows=Files.readAllLines(Path.of("examples/flexible/contract-batches.csv"));
        rows.set(4,rows.get(4).replace(",2026-09-15,GRANT,",",2026-09-15,RVOKE,"));
        Path input=WORK.resolve("projection-conflict.csv");Files.write(input,rows);
        String id=processor.process(input,"error-demo-service-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED_WITH_ERRORS");
        assertThat(json.readTree(ledger.next(id,0).payload())).hasSize(2);
        assertThat(report(id).path("mediumCount").asInt()).isEqualTo(1);
    }
    JsonNode manifest(String type,String id)throws Exception {return json.readTree(WORK.resolve("results").resolve(type).resolve(id).resolve("ready.json").toFile());}
    JsonNode report(String id)throws Exception {var m=manifest("internal",id);return json.readTree(WORK.resolve("results/internal").resolve(id).resolve(m.path("internalFile").asText()).toFile());}
    static Path temp(){try{return Files.createTempDirectory("processing-results-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
