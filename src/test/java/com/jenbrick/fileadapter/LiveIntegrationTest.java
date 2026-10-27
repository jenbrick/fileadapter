package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.config.IngestionProperties;
import com.jenbrick.fileadapter.delivery.HttpUpsertClient;
import com.jenbrick.fileadapter.delivery.UpsertClient;
import com.jenbrick.fileadapter.processing.FileProcessor;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import com.jenbrick.fileadapter.processing.RunSnapshot;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"ingestion.poll-enabled=false", "ingestion.mode=LIVE",
        "ingestion.delivery.replay-safe=true", "ingestion.delivery.retry-delay=1ms",
        "ingestion.delivery.max-attempts=2"})
@DirtiesContext
class LiveIntegrationTest {
    static final Path WORK = temporary();
    static final Queue<Integer> STATUSES = new ConcurrentLinkedQueue<>();
    static final List<String> BODIES = new CopyOnWriteArrayList<>();
    static final List<String> KEYS = new CopyOnWriteArrayList<>();
    static final HttpServer SERVER = server();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("ingestion.work-directory", WORK::toString);
        registry.add("ingestion.delivery.endpoint", () -> endpoint().toString());
    }

    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired ObjectMapper json;
    @Autowired IngestionProperties properties;
    @Autowired HttpUpsertClient httpClient;

    @BeforeEach void reset() { STATUSES.clear(); BODIES.clear(); KEYS.clear(); }
    @AfterAll static void stopServer() { SERVER.stop(0); }

    @Test void postsNestedJsonAndDoesNotResendCompletedContent() throws Exception {
        Path input = input("success", false);
        String id = processor.process(input, null, false);
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        assertThat(BODIES).hasSize(2);
        JsonNode body = json.readTree(BODIES.get(0));
        assertThat(body.at("/person/contact/email").asText()).isEqualTo("jordan@example.com");
        assertThat(KEYS.get(0)).isEqualTo(id + ":1");
        assertThat(KEYS.get(1)).isEqualTo(id + ":2");
        assertThat(processor.process(input, null, false)).isEqualTo(id);
        assertThat(BODIES).hasSize(2);
    }

    @Test void retriesWithSameKeyAndResumesOnlyUnacknowledgedRecords() throws Exception {
        STATUSES.addAll(List.of(201, 503, 503));
        String id = processor.process(input("retry", false), null, false);
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(BODIES).hasSize(3);
        assertThat(KEYS.get(1)).isEqualTo(KEYS.get(2));
        assertThat(processor.resume(id)).isTrue();
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        assertThat(BODIES).hasSize(4);
        assertThat(KEYS.get(3)).isEqualTo(id + ":2");
        assertThat(KEYS.stream().filter(k -> k.equals(id + ":1")).count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enrollment.fw", "enrollment-header.psv", "enrollment-noheader.psv",
            "enrollment.xml", "enrollment-array.json", "enrollment-object.json", "enrollment.jsonl"})
    void allNewFormatsReachTheExistingHttpDeliveryFlow(String filename) throws Exception {
        String id = processor.process(Path.of("examples", filename), null, false);
        int count = filename.contains("object") ? 1 : 2;
        assertThat(ledger.get(id).status()).isEqualTo("COMPLETED");
        assertThat(BODIES).hasSize(count);
        var expected = Files.readAllLines(Path.of("examples/expected-requests.jsonl"));
        for (int i = 0; i < count; i++) {
            assertThat(json.readTree(BODIES.get(i))).isEqualTo(json.readTree(expected.get(i)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"enrollment.fw", "enrollment-header.psv", "enrollment.xml",
            "enrollment-array.json", "enrollment.jsonl"})
    void malformedLaterRecordsAcrossFormatsNeverCausePartialDelivery(String filename) throws Exception {
        String valid = Files.readString(Path.of("examples", filename));
        String invalid = switch (filename) {
            case "enrollment.fw" -> valid + "short\n";
            case "enrollment-header.psv" -> valid + "too|few\n";
            case "enrollment.xml" -> valid.replace("</employees>", "</broken>");
            case "enrollment-array.json" -> valid.stripTrailing().replaceFirst("\\]$", ",null]");
            default -> valid + "{bad-json}\n";
        };
        Path input = WORK.resolve(filename);
        Files.writeString(input, invalid);
        String id = processor.process(input, null, false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(BODIES).isEmpty();
    }

    @Test void rejectsPermanentHttpFailureWithoutSendingLaterRecords() throws Exception {
        STATUSES.add(400);
        String id = processor.process(input("rejected", false), null, false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(BODIES).hasSize(1);
        assertThat(processor.resume(id)).isFalse();
        assertThat(BODIES).hasSize(1);
    }

    @Test void preflightsEntireInputBeforeSendingAnything() throws Exception {
        String id = processor.process(input("malformed", true), null, false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(BODIES).isEmpty();
    }

    @Test void unsafeInterruptedDeliveryPausesWithoutNetworkReplay() throws Exception {
        var settings = properties.delivery();
        var unsafe = new IngestionProperties.Delivery(settings.endpoint(), settings.connectTimeout(),
                settings.readTimeout(), settings.maxAttempts(), settings.retryDelay(), false,
                settings.idempotencyHeader(), settings.successStatuses());
        String id = "a".repeat(64);
        var snapshot = new RunSnapshot(1, properties.profiles().get(0),
                IngestionProperties.Mode.LIVE, unsafe);
        ledger.create(id, "interrupted.csv", "b".repeat(64), json.writeValueAsString(snapshot));
        ledger.stage(id, 1, "{\"employeeId\":\"001\"}");
        ledger.status(id, "DELIVERING", null);
        ledger.sending(id, 1);
        processor.recover();
        assertThat(ledger.get(id).status()).isEqualTo("PAUSED");
        assertThat(processor.resume(id)).isFalse();
        assertThat(BODIES).isEmpty();
    }

    @Test void doesNotTreatAccepted202AsCompletedUnlessContractAllowsIt() {
        STATUSES.add(202);
        var result = httpClient.post(json.createObjectNode().put("id", "202"), "key-202",
                properties.delivery());
        assertThat(result.outcome()).isEqualTo(UpsertClient.Outcome.UNCERTAIN);
        assertThat(result.httpStatus()).isEqualTo(202);
    }

    @Test void recoveryNeverReplaysPreviouslyRecordedPermanentRejection() throws Exception {
        String id = "c".repeat(64);
        var snapshot = new RunSnapshot(1, properties.profiles().get(0),
                IngestionProperties.Mode.LIVE, properties.delivery());
        ledger.create(id, "rejected-before-crash.csv", "d".repeat(64), json.writeValueAsString(snapshot));
        ledger.stage(id, 1, "{\"employeeId\":\"001\"}");
        ledger.sending(id, 1);
        ledger.result(id, 1, "REJECTED", 400);
        ledger.status(id, "DELIVERING", null);
        processor.recover();
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(BODIES).isEmpty();
    }

    @Test void legacyPreviewBackfillNeverPostsEvenWhenCurrentModeIsLive() throws Exception {
        String id = "f".repeat(64);
        var snapshot = new RunSnapshot(1, properties.profiles().get(0),
                IngestionProperties.Mode.PREVIEW, properties.delivery());
        ledger.create(id, "old-preview.csv", "0".repeat(64), json.writeValueAsString(snapshot));
        ledger.stage(id, 1, "{\"employeeId\":\"SAVED001\"}");
        ledger.status(id, "PREVIEWED", null);
        processor.refreshPreviews();
        assertThat(BODIES).isEmpty();
        var labelled = json.readTree(WORK.resolve("preview").resolve(id)
                .resolve("old-preview.csv.preview.json").toFile());
        assertThat(labelled.at("/requests/0/employeeId").asText()).isEqualTo("SAVED001");
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
    }

    @Test void timeoutIsUncertainAndDoesNotExposeResponseData() {
        var settings = new IngestionProperties.Delivery(
                URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/slow"),
                Duration.ofSeconds(1), Duration.ofMillis(20), 1, Duration.ZERO,
                false, "Idempotency-Key", List.of(200));
        var result = httpClient.post(json.createObjectNode().put("id", "timeout"), "timeout-key", settings);
        assertThat(result.outcome()).isEqualTo(UpsertClient.Outcome.UNCERTAIN);
    }

    private Path input(String suffix, boolean malformed) throws Exception {
        Path input = WORK.resolve("enrollment-" + suffix + ".csv");
        String content = Files.readString(Path.of("examples/enrollment.csv"))
                .replace("E1001", "E1001-" + suffix);
        if (malformed) content += "E1003,Later,Failure,,York,PA,DENTAL,bad-date\n";
        Files.writeString(input, content);
        return input;
    }

    private static URI endpoint() {
        return URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/upsert");
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/upsert", exchange -> {
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                KEYS.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                Integer configured = STATUSES.poll();
                int status = configured == null ? 201 : configured;
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.createContext("/slow", exchange -> {
                exchange.getRequestBody().readAllBytes();
                try { Thread.sleep(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static Path temporary() {
        try { return Files.createTempDirectory("fileadapter-live-test-"); }
        catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
