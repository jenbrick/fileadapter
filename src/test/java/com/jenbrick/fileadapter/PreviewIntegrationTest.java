package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.config.IngestionProperties;
import com.jenbrick.fileadapter.processing.DirectoryPoller;
import com.jenbrick.fileadapter.processing.FileProcessor;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"ingestion.poll-enabled=false"})
@DirtiesContext
class PreviewIntegrationTest {
    static final Path WORK = temporary();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("ingestion.work-directory", WORK::toString);
        registry.add("ingestion.input-directory", () -> WORK.resolve("incoming").toString());
    }

    @Autowired FileProcessor processor;
    @Autowired ProcessingLedger ledger;
    @Autowired ObjectMapper json;
    @Autowired IngestionProperties properties;

    @Test void producesExpectedRequestsAndDeduplicatesContent() throws Exception {
        String id = processor.process(Path.of("examples/enrollment.csv"), null, false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        List<String> actual = Files.readAllLines(WORK.resolve("preview").resolve(id).resolve("requests.jsonl"));
        List<String> expected = Files.readAllLines(Path.of("examples/expected-requests.jsonl"));
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < actual.size(); i++) {
            assertThat(json.readTree(actual.get(i))).isEqualTo(json.readTree(expected.get(i)));
        }
        assertThat(Files.exists(WORK.resolve("archive").resolve(id).resolve("input"))).isTrue();
        assertThat(processor.process(Path.of("examples/enrollment.csv"), null, false)).isEqualTo(id);
        assertThat(ledger.count(id)).isEqualTo(2);
        assertThat(Files.exists(Path.of("examples/enrollment.csv"))).isTrue();
    }

    @Test void malformedLaterRowFailsPreflightAndCreatesReport() throws Exception {
        Path file = WORK.resolve("enrollment-invalid.csv");
        Files.writeString(file, Files.readString(Path.of("examples/enrollment.csv"))
                + "E1003,Bad,Date,,York,PA,DENTAL,not-a-date\n");
        String id = processor.process(file, null, false);
        assertThat(ledger.get(id).status()).isEqualTo("FAILED");
        assertThat(Files.exists(WORK.resolve("preview").resolve(id).resolve("requests.jsonl"))).isFalse();
        assertThat(Files.readString(WORK.resolve("errors").resolve(id).resolve("error.json")))
                .contains("PREFLIGHT", "3").doesNotContain("not-a-date");
        assertThat(Files.exists(WORK.resolve("errors").resolve(id).resolve("input-files/input"))).isTrue();
    }

    @Test void pollerRequiresReadyMarkerAndConsumesOnlyAfterStaging() throws Exception {
        var polling = new IngestionProperties(properties.workDirectory(), properties.inputDirectory(),
                properties.mode(), true, java.time.Duration.ZERO, ".ready", null, null, null,
                properties.delivery(), properties.profiles());
        var poller = new DirectoryPoller(polling, processor);
        Files.createDirectories(polling.inputDirectory());
        Path file = polling.inputDirectory().resolve("enrollment-poll.csv");
        Files.writeString(file, Files.readString(Path.of("examples/enrollment.csv"))
                .replace("E1001", "POLL1001"));
        poller.poll();
        assertThat(Files.exists(file)).isTrue();
        Files.createFile(Path.of(file + ".ready"));
        poller.poll();
        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.exists(Path.of(file + ".ready"))).isFalse();
    }

    private static Path temporary() {
        try { return Files.createTempDirectory("fileadapter-preview-test-"); }
        catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
