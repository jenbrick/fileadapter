package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.processing.FileProcessor;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"ingestion.poll-enabled=false","ingestion.mode=LIVE",
    "ingestion.delivery.replay-safe=true","ingestion.delivery.max-attempts=1",
    "CONSENT_SOURCE_TRANSACTION=synthetic.demo","CONSENT_SOURCE_DATETIME=2026-10-27T12:00:00Z"})
@DirtiesContext
class ConsentLiveIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path WORK=temp();
    static final List<JsonNode> BODIES=new CopyOnWriteArrayList<>();
    static final List<String> KEYS=new CopyOnWriteArrayList<>();
    static final Queue<Integer> STATUSES=new ConcurrentLinkedQueue<>();
    static final AtomicInteger LOOKUPS=new AtomicInteger();
    static int lookupStatus=200;
    static final HttpServer SERVER=server();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("ingestion.work-directory",WORK::toString);
        r.add("CONSENT_LOOKUP_URL",()->base()+"/resolve");
        r.add("ingestion.delivery.endpoint",()->base()+"/upsert");
    }
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired com.jenbrick.fileadapter.config.ProfileCatalog catalog;
    @Autowired com.jenbrick.fileadapter.config.IngestionProperties properties;
    @Autowired ObjectMapper objectMapper;
    @BeforeEach void reset(){BODIES.clear();KEYS.clear();STATUSES.clear();LOOKUPS.set(0);lookupStatus=200;}
    @AfterAll static void stop(){SERVER.stop(0);}
    @Test void postsOneConsentArrayPerMemberAfterEnrichment() throws Exception {
        String id=processor.process(input("success"),"cms-consent-csv-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        assertThat(LOOKUPS.get()).isEqualTo(4);
        var expected=Files.readAllLines(Path.of("examples/consent/expected-requests.jsonl"));
        assertThat(BODIES).hasSize(4);
        for(int i=0;i<4;i++) assertThat(BODIES.get(i)).isEqualTo(JSON.readTree(expected.get(i)));
    }
    @Test void recoverySkipsAcknowledgedMemberAndNeverRepeatsLookups() throws Exception {
        STATUSES.addAll(List.of(201,503));
        String id=processor.process(input("partial"),"cms-consent-csv-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(BODIES).hasSize(2);
        var report=JSON.readTree(WORK.resolve("errors").resolve(id).resolve("error.json").toFile());
        assertThat(report.path("sourceRecordNumber").longValue()).isEqualTo(2);
        assertThat(report.path("requestNumber").longValue()).isEqualTo(2);
        assertThat(processor.resume(id)).isTrue();
        assertThat(BODIES).hasSize(5);
        assertThat(KEYS.get(0)).isEqualTo(id+":1");
        assertThat(KEYS.get(1)).isEqualTo(KEYS.get(2));
        assertThat(LOOKUPS.get()).isEqualTo(4);
        assertThat(BODIES.get(1)).isEqualTo(BODIES.get(2));
    }
    @Test void retriesWholeTwoEntryArrayWithOneStableKey() throws Exception {
        STATUSES.add(503);
        String id=processor.process(input("whole-array-retry"),"cms-consent-csv-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(BODIES).hasSize(1);
        assertThat(BODIES.get(0).isArray()).isTrue();
        assertThat(BODIES.get(0)).hasSize(2);
        assertThat(processor.resume(id)).isTrue();
        assertThat(BODIES).hasSize(5);
        assertThat(BODIES.get(0)).isEqualTo(BODIES.get(1));
        assertThat(KEYS.get(0)).isEqualTo(KEYS.get(1));
        assertThat(LOOKUPS.get()).isEqualTo(4);
    }
    @Test void version2PendingRunPausesBeforeAnyLegacyObjectCanBePosted() throws Exception {
        String id="2".repeat(64);
        Path source=input("legacy-v2");
        byte[] bytes=Files.readAllBytes(source);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var snapshot=new com.jenbrick.fileadapter.processing.RunSnapshot(2,
                catalog.resolve(source,"cms-consent-csv-v1"),
                com.jenbrick.fileadapter.config.IngestionProperties.Mode.LIVE,properties.delivery());
        ledger.create(id,source.getFileName().toString(),hash,objectMapper.writeValueAsString(snapshot));
        Path dir=WORK.resolve("processing").resolve(id);Files.createDirectories(dir);
        Files.copy(source,dir.resolve("input"));
        Files.writeString(dir.resolve("snapshot.json"),objectMapper.writeValueAsString(snapshot));
        processor.recover();
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(BODIES).isEmpty();
        assertThat(LOOKUPS.get()).isZero();
        assertThat(processor.resume(id)).isFalse();
        assertThat(BODIES).isEmpty();
    }
    @Test void lookupFailureBlocksEveryUpsert() throws Exception {
        lookupStatus=404;
        String id=processor.process(input("unresolved"),"cms-consent-csv-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(BODIES).isEmpty();
    }
    @Test void badLaterRowBlocksEarlierValidConsents() throws Exception {
        Path file=input("invalid");
        Files.writeString(file,Files.readString(file).replace("2026-10-01,GRANT","2026-02-30,GRANT"));
        String id=processor.process(file,"cms-consent-csv-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(BODIES).isEmpty();
    }
    static HttpServer server(){
        try{
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/resolve",exchange->{
                LOOKUPS.incrementAndGet();
                var criteria=JSON.readTree(exchange.getRequestBody());
                var fixtures=JSON.readTree(Path.of("examples/consent/lookup-fixtures.json").toFile());
                JsonNode resolution=null;
                for(var fixture:fixtures) if(fixture.at("/match/EXTERNAL_CONTRACT_ID").asText().equals(criteria.path("EXTERNAL_CONTRACT_ID").asText())) resolution=fixture.get("result");
                byte[] body=JSON.writeValueAsBytes(resolution);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(lookupStatus,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });
            server.createContext("/upsert",exchange->{
                BODIES.add(JSON.readTree(exchange.getRequestBody()));
                KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                Integer status=STATUSES.poll();exchange.sendResponseHeaders(status==null?201:status,-1);exchange.close();
            });server.start();return server;
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static String base(){return "http://127.0.0.1:"+SERVER.getAddress().getPort();}
    static Path temp(){try{return Files.createTempDirectory("consent-live-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static Path input(String name)throws Exception{Path p=WORK.resolve(name+".csv");Files.writeString(p,Files.readString(Path.of("examples/consent/consent.csv")).replace("Synthetic Health","Synthetic "+name));return p;}
}
