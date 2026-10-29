package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.processing.FileProcessor;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"ingestion.mode=LIVE","FLEXIBLE_LOOKUP_MODE=HTTP",
        "ingestion.delivery.replay-safe=true","ingestion.delivery.max-attempts=1"})
@ActiveProfiles("flexible-demo")
@DirtiesContext
class FlexibleProjectionLiveTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path WORK=temp();
    static final List<JsonNode> BODIES=new CopyOnWriteArrayList<>();
    static final List<String> KEYS=new CopyOnWriteArrayList<>();
    static final AtomicInteger LOOKUPS=new AtomicInteger();
    static volatile int status=201;
    static volatile String providerGuid;
    static final HttpServer SERVER=server();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        r.add("ingestion.work-directory",WORK::toString);
        r.add("CONSENT_LOOKUP_URL",()->base()+"/resolve");r.add("ingestion.delivery.endpoint",()->base()+"/upsert");
    }
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @BeforeEach void reset(){BODIES.clear();KEYS.clear();LOOKUPS.set(0);status=201;providerGuid=null;}
    @AfterAll static void stop(){SERVER.stop(0);}
    @Test void configuredEnvelopeReachesHttpWithoutShapeSpecificDeliveryCode()throws Exception{
        String id=processor.process(input("http"),"flexible-json-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        assertThat(BODIES).hasSize(1);
        assertThat(BODIES.get(0)).isEqualTo(JSON.readTree(Path.of("examples/flexible/expected-envelope.json").toFile()));
        assertThat(LOOKUPS.get()).isEqualTo(3);
    }
    @Test void resolvedExistingGuidIsMappedRatherThanReplacedByTheNullDefault()throws Exception{
        providerGuid="EXISTING-PROVIDER-GUID";
        String id=processor.process(input("existing-guid"),"flexible-json-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        assertThat(BODIES.get(0).at("/consents/0/consentGuid").asText()).isEqualTo(providerGuid);
    }
    @Test void retryReusesTheWholeBatchAndAllSourceOriginsWithoutAnotherLookup()throws Exception{
        status=503;
        String id=processor.process(input("retry"),"flexible-json-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(BODIES).hasSize(1);
        var report=JSON.readTree(WORK.resolve("errors").resolve(id).resolve("error.json").toFile());
        assertThat(report.path("sourceRecordNumbers").toString()).isEqualTo("[1,2,3]");
        status=201;
        assertThat(processor.resume(id)).isTrue();
        assertThat(BODIES).hasSize(2);
        assertThat(BODIES.get(0)).isEqualTo(BODIES.get(1));
        assertThat(KEYS.get(0)).isEqualTo(KEYS.get(1));
        assertThat(LOOKUPS.get()).isEqualTo(3);
    }
    @Test void groupedConflictBlocksEveryHttpPost()throws Exception{
        Path path=input("conflict");var rows=JSON.readTree(path.toFile());
        ((ObjectNode)rows.get(2)).put("PROVIDER_CLINICAL_CONSENT_PREFERENCE","RVOKE");JSON.writeValue(path.toFile(),rows);
        String id=processor.process(path,"flexible-json-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");assertThat(BODIES).isEmpty();
    }
    static Path input(String name)throws Exception{
        var rows=JSON.readTree(Path.of("examples/flexible/flexible-consents.json").toFile());
        for(var row:rows)((ObjectNode)row).put("TEST_CASE",name);
        Path path=WORK.resolve(name+".json");JSON.writeValue(path.toFile(),rows);return path;
    }
    static HttpServer server(){try{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/resolve",exchange->{
            LOOKUPS.incrementAndGet();var criteria=JSON.readTree(exchange.getRequestBody());
            String id=criteria.path("EXTERNAL_CONTRACT_ID").asText().endsWith("001")?"001":"002";
            var identity=JSON.createObjectNode().put("contract","123456789").put("pin",id).put("contractMemberGuid","MEMBER-GUID-"+id);
            var result=JSON.createObjectNode();result.set("owner",identity);result.set("manager",identity);
            result.put("providerConsentGuid",id.equals("001")?providerGuid:null);result.putNull("payerConsentGuid");byte[] body=JSON.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/upsert",exchange->{BODIES.add(JSON.readTree(exchange.getRequestBody()));KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));exchange.sendResponseHeaders(status,-1);exchange.close();});
        server.start();return server;
    }catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static String base(){return "http://127.0.0.1:"+SERVER.getAddress().getPort();}
    static Path temp(){try{return Files.createTempDirectory("flexible-live-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
