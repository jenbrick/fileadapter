package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.*;
import com.jenbrick.fileadapter.processing.*;
import com.jenbrick.fileadapter.projection.*;
import com.jenbrick.fileadapter.source.SourceRecord;
import com.sun.net.httpserver.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"ingestion.mode=LIVE","FLEXIBLE_LOOKUP_MODE=HTTP","GROUP_LOOKUP_MODE=HTTP",
        "ingestion.delivery.replay-safe=true","ingestion.delivery.max-attempts=1"})
@ActiveProfiles("flexible-demo")
@DirtiesContext
class GroupMemberLookupTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path WORK=temp();
    static final List<String> EVENTS=new CopyOnWriteArrayList<>();
    static final List<JsonNode> GROUP_BODIES=new CopyOnWriteArrayList<>(),MEMBER_BODIES=new CopyOnWriteArrayList<>();
    static final AtomicInteger POSTS=new AtomicInteger();
    static volatile int groupStatus=200,memberStatus=200,upsertStatus=201;
    static volatile boolean incompleteGroup=false;
    static final HttpServer SERVER=server();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("ingestion.work-directory",WORK::toString);r.add("GROUP_LOOKUP_URL",()->base()+"/group");
        r.add("CONSENT_LOOKUP_URL",()->base()+"/member");r.add("ingestion.delivery.endpoint",()->base()+"/upsert");
    }
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired ProjectionEngine engine;
    @Autowired ProfileCatalog profiles;
    @BeforeEach void reset(){EVENTS.clear();GROUP_BODIES.clear();MEMBER_BODIES.clear();POSTS.set(0);groupStatus=200;memberStatus=200;upsertStatus=201;incompleteGroup=false;}
    @AfterAll static void stop(){SERVER.stop(0);}
    @Test void groupIsResolvedFirstAndMemberRequestContainsOnlyTheAllowedMemberFields()throws Exception {
        String id=run("ordered");assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        assertThat(EVENTS).containsExactly("GROUP","MEMBER","MEMBER","MEMBER","UPSERT");
        assertThat(GROUP_BODIES).hasSize(1);
        assertThat(GROUP_BODIES.get(0)).isEqualTo(JSON.readTree("{\"externalGroupId\":\"EXT-GROUP-1\"}"));
        assertThat(MEMBER_BODIES).hasSize(3);
        for(var body:MEMBER_BODIES) {
            var fields=new HashSet<String>();body.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("externalContractId","relationshipCode","firstName","middleName","lastName","genderCode","dateOfBirth","internalGroupNumber","customerId");
            assertThat(body.path("internalGroupNumber").asText()).isEqualTo("10001");assertThat(body.path("customerId").asText()).isEqualTo("CUST-1");
            assertThat(body.path("dateOfBirth").asText()).isEqualTo("1980-01-01");
        }
    }
    @Test void failedGroupCallPreventsMemberLookupAndUpsert()throws Exception {
        groupStatus=503;String id=run("group-failure");assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(MEMBER_BODIES).isEmpty();assertThat(POSTS.get()).isZero();
    }
    @Test void incompleteGroupResponseCannotReachTheMemberService()throws Exception {
        incompleteGroup=true;String id=run("incomplete-group");assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(MEMBER_BODIES).isEmpty();assertThat(POSTS.get()).isZero();
    }
    @Test void missingExternalGroupIdDoesNotSendAnyLookup()throws Exception {
        var rows=input("missing-group");for(var row:rows)((ObjectNode)row).put("EXTERNAL_GROUP_ID","");
        String id=process("missing-group",rows);assertThat(ledger.get(id).status()).isEqualTo("FAILED");assertThat(GROUP_BODIES).isEmpty();assertThat(MEMBER_BODIES).isEmpty();
    }
    @Test void deliveryResumeUsesSavedGroupAndMemberResults()throws Exception {
        upsertStatus=503;String id=run("delivery-resume");assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        upsertStatus=201;assertThat(processor.resume(id)).isTrue();
        assertThat(GROUP_BODIES).hasSize(1);assertThat(MEMBER_BODIES).hasSize(3);assertThat(POSTS.get()).isEqualTo(2);
    }
    @Test void groupContextSurvivesAnInterruptedRowEnrichment()throws Exception {
        var profile=profiles.resolve(Path.of("flexible-consents.json"),null);
        String id="1".repeat(64);ledger.create(id,"row.json","2".repeat(64),"{}");
        var row=new SourceRecord(1,input("interrupted").get(0));memberStatus=503;
        assertThatThrownBy(()->engine.enrich(id,row,profile,IngestionProperties.Mode.LIVE)).isInstanceOf(IllegalArgumentException.class);
        memberStatus=200;var resolved=engine.enrich(id,row,profile,IngestionProperties.Mode.LIVE);
        assertThat(GROUP_BODIES).hasSize(1);assertThat(MEMBER_BODIES).hasSize(2);
        assertThat(resolved.payload().at("/resolvedGroup/customerId").asText()).isEqualTo("CUST-1");
    }
    @Test void groupMustPrecedeMemberInTheConfiguredChain() {
        var base=profiles.resolve(Path.of("flexible-consents.json"),null);var p=base.projection();
        var reversed=new Projection(p.version(),p.scope(),p.maxRecords(),List.of("consent-member","group-context"),p.inputContract(),p.skipWhenEmpty(),p.root(),p.batching());
        var profile=new Profile(base.id(),base.filenamePattern(),base.layout(),base.consent(),reversed,base.errors(),base.group());
        assertThatThrownBy(()->engine.validateEnrichers(profile)).hasMessageContaining("must precede");
    }
    @Test void noChangeRowsNeedNeitherGroupNorMemberLookup()throws Exception {
        var rows=input("no-change");for(var row:rows){((ObjectNode)row).put("PROVIDER_CLINICAL_CONSENT_PREFERENCE","");((ObjectNode)row).put("PAYER_CONSENT_PREFERENCE","");((ObjectNode)row).put("EXTERNAL_GROUP_ID","");}
        String id=process("no-change",rows);assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");assertThat(EVENTS).isEmpty();
    }
    String run(String name)throws Exception{return process(name,input(name));}
    static JsonNode input(String name)throws Exception {var rows=JSON.readTree(Path.of("examples/flexible/flexible-consents.json").toFile());for(var r:rows)((ObjectNode)r).put("TEST_CASE",name);return rows;}
    String process(String name,JsonNode rows)throws Exception {Path file=WORK.resolve(name+".json");JSON.writeValue(file.toFile(),rows);return processor.process(file,"flexible-json-v1",false);}
    static HttpServer server(){try{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/group",e->{EVENTS.add("GROUP");GROUP_BODIES.add(JSON.readTree(e.getRequestBody()));
            var response=JSON.createObjectNode().put("internalGroupNumber","10001");if(!incompleteGroup)response.put("customerId","CUST-1");send(e,groupStatus,response);});
        server.createContext("/member",e->{EVENTS.add("MEMBER");var member=JSON.readTree(e.getRequestBody());MEMBER_BODIES.add(member);
            String pin=member.path("externalContractId").asText().endsWith("001")?"001":"002";
            var owner=JSON.createObjectNode().put("contract","123456789").put("pin",pin).put("contractMemberGuid","MEMBER-GUID-"+pin);
            var response=JSON.createObjectNode();response.set("owner",owner);response.set("manager",owner);send(e,memberStatus,response);});
        server.createContext("/upsert",e->{EVENTS.add("UPSERT");POSTS.incrementAndGet();e.getRequestBody().readAllBytes();send(e,upsertStatus,JSON.createObjectNode());});server.start();return server;
    }catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static void send(HttpExchange e,int status,JsonNode body)throws java.io.IOException {byte[] data=JSON.writeValueAsBytes(body);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();}
    static String base(){return "http://127.0.0.1:"+SERVER.getAddress().getPort();}
    static Path temp(){try{return Files.createTempDirectory("group-member-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
