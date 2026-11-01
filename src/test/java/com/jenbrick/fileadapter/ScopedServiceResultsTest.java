package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.jenbrick.fileadapter.processing.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"ingestion.mode=LIVE","FLEXIBLE_LOOKUP_MODE=HTTP",
        "ingestion.delivery.response-contract=SCOPED_V1","ingestion.delivery.replay-safe=true","ingestion.delivery.max-attempts=1"})
@ActiveProfiles("flexible-demo")
@DirtiesContext
class ScopedServiceResultsTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path WORK=temp();
    static final AtomicInteger POSTS=new AtomicInteger(),LOOKUPS=new AtomicInteger();
    static volatile String scope="MEMBER",member="MEMBER-GUID-002",code="MEMBER_INVALID";
    static volatile int failAt=0,hardAt=0;
    static volatile boolean complete=true;
    static final HttpServer SERVER=server();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        r.add("ingestion.work-directory",WORK::toString);r.add("CONSENT_LOOKUP_URL",()->base()+"/resolve");r.add("ingestion.delivery.endpoint",()->base()+"/upsert");
    }
    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @BeforeEach void reset(){POSTS.set(0);LOOKUPS.set(0);scope="MEMBER";member="MEMBER-GUID-002";code="MEMBER_INVALID";failAt=0;hardAt=0;complete=true;}
    @AfterAll static void stop(){SERVER.stop(0);}
    @Test void memberRejectionReturnsOnlyThatMembersOriginalRows()throws Exception {
        String id=run("member");
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED_WITH_ERRORS");assertThat(POSTS.get()).isEqualTo(2);
        var returned=customer(id);assertThat(returned).contains("John").doesNotContain("Alex,","Morgan,","Taylor,","Jamie,");
        assertThat(returned.lines().count()).isEqualTo(4); // header plus John's three original coverage/consent rows
        assertThat(report(id).path("mediumCount").asInt()).isEqualTo(1);
        assertThat(report(id).toString()).doesNotContain("SENSITIVE-UNTRUSTED");
    }
    @Test void contractRejectionReturnsAllMembersInOnlyThatContract()throws Exception {
        scope="CONTRACT";code="CONTRACT_INVALID";
        String id=run("contract");assertThat(ledger.get(id).status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(customer(id).lines().count()).isEqualTo(5);assertThat(customer(id)).contains("Alex").contains("John");
    }
    @Test void malformedOrIncompleteServiceResultsPauseWithoutCustomerFailures()throws Exception {
        complete=false;String id=run("incomplete");assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(Files.exists(WORK.resolve("results/customer").resolve(id).resolve("ready.json"))).isFalse();
    }
    @Test void unmatchedServiceIdentityCannotRejectAnUnrelatedMember()throws Exception {
        member="NOT-A-SUBMITTED-MEMBER";String id=run("unmatched");assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(POSTS.get()).isEqualTo(1);
        assertThat(Files.exists(WORK.resolve("results/customer").resolve(id).resolve("ready.json"))).isFalse();
        assertThat(ledger.next(id,0).status()).isEqualTo("ACKNOWLEDGED");
        assertThat(processor.resume(id)).isFalse();assertThat(POSTS.get()).isEqualTo(1);
    }
    @Test void acknowledgedPartialResultsSurviveResumeWithoutDuplicateFindingsOrLookup()throws Exception {
        failAt=2;String id=run("resume");assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        failAt=0;assertThat(processor.resume(id)).isTrue();assertThat(POSTS.get()).isEqualTo(3);assertThat(LOOKUPS.get()).isEqualTo(8);
        assertThat(report(id).path("mediumCount").asInt()).isEqualTo(1);assertThat(customer(id).lines().count()).isEqualTo(4);
    }
    @Test void unknownServiceCodePausesInsteadOfExposingRawServiceText()throws Exception {
        code="UNKNOWN_CODE";String id=run("unknown-code");assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(Files.exists(WORK.resolve("results/customer").resolve(id).resolve("ready.json"))).isFalse();
    }
    @Test void liveHardStopDoesNotReturnMembersWhoseEarlierBatchSucceeded()throws Exception {
        hardAt=2;String id=run("hard-service");assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(POSTS.get()).isEqualTo(2);assertThat(customer(id).lines().count()).isEqualTo(2);
        assertThat(customer(id)).contains("Jamie").doesNotContain("John","Alex","Morgan","Taylor");
    }
    String run(String suffix)throws Exception {
        Path file=WORK.resolve("contract-"+suffix+".csv");Files.copy(Path.of("examples/flexible/contract-batches.csv"),file);
        // Source contents participate in run identity; include a distinct harmless source user per test.
        Files.writeString(file,Files.readString(file).replace("my.user.name",suffix));
        return processor.process(file,"error-demo-service-v1",false);
    }
    JsonNode report(String id)throws Exception {
        var dir=WORK.resolve("results/internal").resolve(id);var m=JSON.readTree(dir.resolve("ready.json").toFile());return JSON.readTree(dir.resolve(m.path("internalFile").asText()).toFile());
    }
    String customer(String id)throws Exception {
        var dir=WORK.resolve("results/customer").resolve(id);var m=JSON.readTree(dir.resolve("ready.json").toFile());return Files.readString(dir.resolve(m.path("customerFile").asText()));
    }
    static HttpServer server(){try{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/resolve",e->{LOOKUPS.incrementAndGet();var input=JSON.readTree(e.getRequestBody());int i=Map.of("Alex",1,"John",2,"Morgan",3,"Taylor",4,"Jamie",5).get(input.path("FIRST_NAME").asText());
            var identity=JSON.createObjectNode().put("contract",i<=2?"123456789":i<=4?"222222222":"333333333").put("pin",String.format("%03d",i<=4?(i-1)%2+1:1)).put("contractMemberGuid",String.format("MEMBER-GUID-%03d",i));
            var result=JSON.createObjectNode();result.set("owner",identity);result.set("manager",identity);result.putNull("providerConsentGuid");result.putNull("payerConsentGuid");send(e,200,JSON.writeValueAsBytes(result));});
        server.createContext("/upsert",e->{int n=POSTS.incrementAndGet();var input=JSON.readTree(e.getRequestBody());if(n==failAt){send(e,503,new byte[0]);return;}
            var result=JSON.createObjectNode().put("complete",complete);var issues=result.putArray("results");
            boolean includesA=false;for(var c:input)if(c.path("contractId").asText().equals("123456789"))includesA=true;
            if(hardAt>0 && n==hardAt)issues.addObject().put("severity","HARD").put("scope","FILE").put("code","FILE_INVALID");
            if(includesA && hardAt==0)issues.addObject().put("severity","MEDIUM").put("scope",scope).put("contractId","123456789").put("memberGuid",member).put("code",code).put("remarks","SENSITIVE-UNTRUSTED-RESPONSE-TEXT");
            send(e,200,JSON.writeValueAsBytes(result));});server.start();return server;
    }catch(Exception e){throw new ExceptionInInitializerError(e);}}
    static void send(com.sun.net.httpserver.HttpExchange e,int status,byte[] body)throws java.io.IOException {e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,body.length==0?-1:body.length);if(body.length>0)e.getResponseBody().write(body);e.close();}
    static String base(){return "http://127.0.0.1:"+SERVER.getAddress().getPort();}
    static Path temp(){try{return Files.createTempDirectory("scoped-results-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
}
