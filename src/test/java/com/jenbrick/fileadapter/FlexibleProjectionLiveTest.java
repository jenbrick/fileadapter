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

/**
 * Use local HTTP services to check the exact delivered bodies, acknowledgements, and restart behavior.
 */
@SpringBootTest(properties={"ingestion.mode=LIVE","FLEXIBLE_LOOKUP_MODE=HTTP","GROUP_LOOKUP_MODE=HTTP",
        "ingestion.delivery.replay-safe=true","ingestion.delivery.max-attempts=1"})
@ActiveProfiles("flexible-demo")
@DirtiesContext
class FlexibleProjectionLiveTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final Path WORK=temp();
    static final List<JsonNode> BODIES=new CopyOnWriteArrayList<>();
    static final List<String> KEYS=new CopyOnWriteArrayList<>();
    static final AtomicInteger LOOKUPS=new AtomicInteger();
    static volatile int status=201;
    static volatile int failRequest=0;
    static volatile int lookupStatus=200;
    static volatile String providerGuid;
    static final HttpServer SERVER=server();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        r.add("ingestion.work-directory",WORK::toString);
        r.add("CONSENT_LOOKUP_URL",()->base()+"/resolve");r.add("GROUP_LOOKUP_URL",()->base()+"/groups");r.add("ingestion.delivery.endpoint",()->base()+"/upsert");
    }
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired com.jenbrick.fileadapter.config.ProfileCatalog profiles;
    @Autowired com.jenbrick.fileadapter.config.IngestionProperties properties;
    @BeforeEach void reset(){BODIES.clear();KEYS.clear();LOOKUPS.set(0);status=201;lookupStatus=200;providerGuid=null;failRequest=0;}
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
    // The first POST is not acknowledged. Resume must keep its body and key, and must not look up
    // the member again.
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
    @Test void lookupFailureBlocksEveryUpsert()throws Exception {
        lookupStatus=503;
        String id=processor.process(input("lookup-failure"),"flexible-json-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(BODIES).isEmpty();
    }
    @Test void permanentlyRejectedRequestsAreNeverReplayed()throws Exception {
        status=400;
        String id=processor.process(input("rejected"),"flexible-json-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        status=201;
        assertThat(processor.resume(id)).isFalse();
        assertThat(BODIES).hasSize(1);
    }
    @Test void accepted202IsNotACompletedAcknowledgement()throws Exception {
        status=202;
        String id=processor.process(input("accepted"),"flexible-json-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(ledger.next(id,0).status()).isEqualTo("UNCERTAIN");
    }
    @Test void interruptedUnsafeDeliveryDoesNotReplay()throws Exception {
        String id="a".repeat(64);
        var d=properties.delivery();
        var unsafe=new com.jenbrick.fileadapter.config.IngestionProperties.Delivery(d.endpoint(),d.connectTimeout(),
                d.readTimeout(),d.maxAttempts(),d.retryDelay(),false,d.idempotencyHeader(),d.successStatuses());
        var snapshot=new com.jenbrick.fileadapter.processing.RunSnapshot(4,
                profiles.resolve(Path.of("flexible-consents.csv"),null),
                com.jenbrick.fileadapter.config.IngestionProperties.Mode.LIVE,unsafe);
        ledger.create(id,"interrupted.csv","b".repeat(64),JSON.writeValueAsString(snapshot));
        ledger.stage(id,1,List.of(1L,2L),"{\"consents\":[]}");
        ledger.sending(id,1);ledger.status(id,"DELIVERING",null);
        assertThat(processor.resume(id)).isFalse();
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(BODIES).isEmpty();
    }
    @Test void oldProcessorSnapshotsAreRejectedBeforeAnyPost()throws Exception {
        String id="c".repeat(64);
        ledger.create(id,"old.csv","d".repeat(64),"{\"engineVersion\":3,\"profile\":{}}");
        ledger.stage(id,1,List.of(1L),"{\"old\":true}");ledger.status(id,"READY",null);
        assertThatThrownBy(()->processor.resume(id)).hasMessageContaining("projection snapshots only");
        assertThat(BODIES).isEmpty();
    }
    // Keep recovery support for already staged bodies even when their old lookup settings are no
    // longer used for new files.
    @Test void previouslySavedFlexibleSnapshotsRemainReadable()throws Exception {
        String id="e".repeat(64);
        var snapshot=JSON.valueToTree(new com.jenbrick.fileadapter.processing.RunSnapshot(4,
                profiles.resolve(Path.of("flexible-consents.csv"),null),
                com.jenbrick.fileadapter.config.IngestionProperties.Mode.LIVE,properties.delivery()));
        var profile=(ObjectNode)snapshot.get("profile");profile.putNull("mappings");profile.putNull("contract");
        profile.remove("group");((ObjectNode)profile.get("projection")).putArray("enrichers").add("consent-member");
        ((ObjectNode)profile.get("consent")).put("sourceSystem","MDB").put("sourceTransactionIdentifier","old-setting");
        ledger.create(id,"saved-flexible.csv","f".repeat(64),JSON.writeValueAsString(snapshot));
        ledger.stage(id,1,List.of(1L,2L,3L),Files.readString(Path.of("examples/flexible/expected-envelope.json")));
        Files.createDirectories(WORK.resolve("processing").resolve(id));
        Files.writeString(WORK.resolve("processing").resolve(id).resolve("input"),"saved source");
        ledger.status(id,"READY",null);
        assertThat(processor.resume(id)).isTrue();
        assertThat(BODIES).hasSize(1);assertThat(LOOKUPS.get()).isZero();
    }
    // One contract succeeds before the next pauses. Only the unacknowledged request should be sent
    // again.
    @Test void contractResumeSkipsAcknowledgedContractsAndKeepsTheFailedBodyAndKey()throws Exception {
        failRequest=2;
        String id=processor.process(Path.of("examples/flexible/contract-batches.csv"),"flexible-contract-single-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(BODIES).hasSize(2);
        assertThat(ledger.next(id,0).status()).isEqualTo("ACKNOWLEDGED");
        failRequest=0;
        assertThat(processor.resume(id)).isTrue();
        assertThat(BODIES).hasSize(4);
        assertThat(BODIES.get(1)).isEqualTo(BODIES.get(2));
        assertThat(KEYS.get(1)).isEqualTo(KEYS.get(2));
        assertThat(KEYS.get(0)).isNotEqualTo(KEYS.get(1));
        assertThat(LOOKUPS.get()).isEqualTo(8);
        var expected=JSON.readTree(Path.of("examples/flexible/expected-contract-single-requests.json").toFile());
        assertThat(BODIES.get(0)).isEqualTo(expected.get(0));
        assertThat(BODIES.get(3)).isEqualTo(expected.get(2));
    }
    @Test void contractArrayBatchesReachHttpWithTheConfiguredLimit()throws Exception {
        String id=processor.process(Path.of("examples/flexible/contract-batches.csv"),"flexible-contract-array-v1",false);
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        var expected=JSON.readTree(Path.of("examples/flexible/expected-contract-array-requests.json").toFile());
        assertThat(BODIES).hasSize(2);
        assertThat(BODIES.get(0)).isEqualTo(expected.get(0));
        assertThat(BODIES.get(1)).isEqualTo(expected.get(1));
    }
    static Path input(String name)throws Exception{
        var rows=JSON.readTree(Path.of("examples/flexible/flexible-consents.json").toFile());
        for(var row:rows)((ObjectNode)row).put("TEST_CASE",name);
        Path path=WORK.resolve(name+".json");JSON.writeValue(path.toFile(),rows);return path;
    }
    // These localhost endpoints stand in for group lookup, member lookup and consent delivery; no
    // company service is called.
    static HttpServer server(){try{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/groups",exchange->{
            var request=JSON.readTree(exchange.getRequestBody());
            int group=Integer.parseInt(request.path("externalGroupId").asText().replace("EXT-GROUP-",""));
            var result=JSON.createObjectNode().put("internalGroupNumber",group+"000"+group).put("customerId","CUST-"+group);
            byte[] body=JSON.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/resolve",exchange->{
            LOOKUPS.incrementAndGet();
            if(lookupStatus!=200){exchange.sendResponseHeaders(lookupStatus,-1);exchange.close();return;}
            var criteria=JSON.readTree(exchange.getRequestBody());
            String external=criteria.path("externalContractId").asText();
            String id=external.startsWith("EXT-CONTRACT-")
                    ? java.util.Map.of("Alex","001","John","002","Morgan","003","Taylor","004","Jamie","005")
                        .get(criteria.path("firstName").asText())
                    : external.substring("EXTERNAL-".length());
            int member=Integer.parseInt(id);
            String contract=member<=2?"123456789":member<=4?"222222222":"333333333";
            String pin=String.format("%03d",member<=4?(member-1)%2+1:1);
            var identity=JSON.createObjectNode().put("contract",contract).put("pin",pin).put("contractMemberGuid","MEMBER-GUID-"+id);
            var result=JSON.createObjectNode();result.set("owner",identity);result.set("manager",identity);
            result.put("providerConsentGuid",id.equals("001")?providerGuid:null);result.putNull("payerConsentGuid");byte[] body=JSON.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/upsert",exchange->{BODIES.add(JSON.readTree(exchange.getRequestBody()));KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));exchange.sendResponseHeaders(failRequest==BODIES.size()?503:status,-1);exchange.close();});
        server.start();return server;
    }catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static String base(){return "http://127.0.0.1:"+SERVER.getAddress().getPort();}
    static Path temp(){try{return Files.createTempDirectory("flexible-live-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
