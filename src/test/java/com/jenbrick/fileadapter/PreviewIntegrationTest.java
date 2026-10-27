package com.jenbrick.fileadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.config.IngestionProperties;
import com.jenbrick.fileadapter.processing.DirectoryPoller;
import com.jenbrick.fileadapter.processing.FileProcessor;
import com.jenbrick.fileadapter.processing.ProcessingLedger;
import com.jenbrick.fileadapter.processing.PreviewWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    @Autowired PreviewWriter previews;

    @Test void producesExpectedRequestsAndDeduplicatesContent() throws Exception {
        String id = processor.process(Path.of("examples/enrollment.csv"), null, false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        List<String> actual = Files.readAllLines(WORK.resolve("preview").resolve(id).resolve("requests.jsonl"));
        List<String> expected = Files.readAllLines(Path.of("examples/expected-requests.jsonl"));
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < actual.size(); i++) {
            assertThat(json.readTree(actual.get(i))).isEqualTo(json.readTree(expected.get(i)));
        }
        var labelled = json.readTree(WORK.resolve("preview").resolve(id)
                .resolve("enrollment.csv.preview.json").toFile());
        assertThat(labelled.get("previewVersion").intValue()).isEqualTo(1);
        assertThat(labelled.get("mode").asText()).isEqualTo("PREVIEW");
        assertThat(labelled.at("/source/fileName").asText()).isEqualTo("enrollment.csv");
        assertThat(labelled.at("/source/fileType").asText()).isEqualTo("CSV");
        assertThat(labelled.at("/source/headerMode").asText()).isEqualTo("FIRST_RECORD");
        assertThat(labelled.at("/source/profileId").asText()).isEqualTo("enrollment-csv-v1");
        assertThat(labelled.at("/source/runId").asText()).isEqualTo(id);
        assertThat(labelled.at("/source/contentSha256").asText()).isEqualTo(ledger.get(id).contentHash());
        assertThat(labelled.at("/source/requestCount").longValue()).isEqualTo(2);
        assertThat(labelled.get("requests")).hasSize(2);
        assertThat(labelled.at("/requests/0")).isEqualTo(json.readTree(expected.get(0)));
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

    @ParameterizedTest
    @ValueSource(strings = {"enrollment.fw", "enrollment-header.psv", "enrollment-noheader.psv",
            "enrollment.xml", "enrollment-array.json", "enrollment-object.json", "enrollment.jsonl"})
    void allNewExamplesProduceTheSameNestedRequests(String filename) throws Exception {
        String id = processor.process(Path.of("examples", filename), null, false);
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        var actual = Files.readAllLines(WORK.resolve("preview").resolve(id).resolve("requests.jsonl"));
        var expected = Files.readAllLines(Path.of("examples/expected-requests.jsonl"));
        int count = filename.contains("object") ? 1 : 2;
        assertThat(actual).hasSize(count);
        for (int i = 0; i < count; i++) {
            assertThat(json.readTree(actual.get(i))).isEqualTo(json.readTree(expected.get(i)));
        }
        var labelled = json.readTree(WORK.resolve("preview").resolve(id)
                .resolve(filename + ".preview.json").toFile());
        assertThat(labelled.at("/source/fileName").asText()).isEqualTo(filename);
        assertThat(labelled.at("/source/requestCount").intValue()).isEqualTo(count);
        assertThat(labelled.get("requests")).hasSize(count);
        for (int i = 0; i < count; i++) {
            assertThat(labelled.get("requests").get(i)).isEqualTo(json.readTree(expected.get(i)));
        }
        switch (filename) {
            case "enrollment.fw" -> {
                assertThat(labelled.at("/source/fileType").asText()).isEqualTo("FIXED_WIDTH");
                assertThat(labelled.at("/source/recordLength").intValue()).isEqualTo(106);
            }
            case "enrollment.xml" -> {
                assertThat(labelled.at("/source/fileType").asText()).isEqualTo("XML");
                assertThat(labelled.at("/source/recordPath").asText()).isEqualTo("/employees/employee");
            }
            case "enrollment-header.psv", "enrollment-noheader.psv" -> {
                assertThat(labelled.at("/source/fileType").asText()).isEqualTo("PIPE_DELIMITED");
                assertThat(labelled.at("/source/delimiter").asText()).isEqualTo("|");
                assertThat(labelled.at("/source/headerMode").asText())
                        .isEqualTo(filename.contains("noheader") ? "NONE" : "FIRST_RECORD");
            }
            default -> {
                assertThat(labelled.at("/source/fileType").asText()).isEqualTo("JSON");
                assertThat(labelled.at("/source/jsonMode").asText()).isEqualTo(
                        filename.contains("array") ? "ARRAY" : filename.contains("object") ? "OBJECT" : "LINES");
            }
        }
        assertThat(processor.process(Path.of("examples", filename), null, false)).isEqualTo(id);
    }

    @Test void refreshAddsSourceDetailsToExistingPreviewsUsingOnlySavedPayloads() throws Exception {
        Path input = WORK.resolve("enrollment-legacy.csv");
        Files.writeString(input, Files.readString(Path.of("examples/enrollment.csv"))
                .replace("E1001", "LEGACY1001"));
        String id = processor.process(input, null, false);
        var run = ledger.get(id);
        Path labelled = previews.previewPath(run);
        Path raw = labelled.resolveSibling("requests.jsonl");
        String savedRaw = Files.readString(raw);
        Files.delete(labelled); // Simulate a preview generated before source-labelled exports existed.
        Files.delete(input); // Backfill cannot depend on the original file or parse it again.
        processor.refreshPreviews();
        assertThat(Files.exists(labelled)).isTrue();
        assertThat(Files.readString(raw)).isEqualTo(savedRaw);
        assertThat(json.readTree(labelled.toFile()).at("/source/fileName").asText())
                .isEqualTo("enrollment-legacy.csv");
        assertThat(ledger.get(id).status()).isEqualTo("PREVIEWED");
        assertThat(ledger.count(id)).isEqualTo(2);
    }

    @Test void previewFilenamesArePortableAndRemainWithinTheRunDirectory() {
        var run = new ProcessingLedger.Run("e".repeat(64), "../odd:name with spaces?.csv", "hash", "{}",
                "PREVIEWED", null);
        Path preview = previews.previewPath(run);
        assertThat(preview.getParent()).isEqualTo(WORK.resolve("preview").resolve(run.id()));
        assertThat(preview.getFileName().toString()).isEqualTo(".._odd_name_with_spaces_.csv.preview.json");
        var reserved = new ProcessingLedger.Run(run.id(), "CON.csv", "hash", "{}", "PREVIEWED", null);
        assertThat(previews.previewPath(reserved).getFileName().toString())
                .isEqualTo("source-CON.csv.preview.json");
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
