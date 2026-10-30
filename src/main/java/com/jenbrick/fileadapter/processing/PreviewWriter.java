package com.jenbrick.fileadapter.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.IngestionProperties;
import com.jenbrick.fileadapter.config.LayoutValidator;
import com.jenbrick.fileadapter.config.Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Human-readable source-labelled previews alongside the exact POST-body JSON Lines. */
@Component
public class PreviewWriter {
    private final ProcessingLedger ledger;
    private final ObjectMapper json;
    private final Path root;

    public PreviewWriter(ProcessingLedger ledger, ObjectMapper json, IngestionProperties properties) {
        this.ledger = ledger;
        this.json = json;
        root = properties.workDirectory().toAbsolutePath().normalize().resolve("preview");
    }

    public Path previewPath(ProcessingLedger.Run run) {
        // Keep names portable across Linux and Windows, retaining the real name in metadata.
        String filename = run.filename().replaceAll("[^A-Za-z0-9._-]", "_");
        if (filename.length() > 120) filename = filename.substring(0, 120);
        String stem = filename.split("\\.", 2)[0];
        if (stem.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) filename = "source-" + filename;
        return root.resolve(run.id()).resolve(filename + ".preview.json");
    }

    public boolean exists(ProcessingLedger.Run run) {
        return Files.isRegularFile(previewPath(run))
                && Files.isRegularFile(root.resolve(run.id()).resolve("requests.jsonl"));
    }

    public Path write(ProcessingLedger.Run run, RunSnapshot snapshot) throws IOException {
        if (snapshot.mode() != IngestionProperties.Mode.PREVIEW) {
            throw new IllegalArgumentException("Only preview runs can generate preview artifacts");
        }
        Path labelled = previewPath(run);
        Files.createDirectories(labelled.getParent());
        Path pendingLabelled = labelled.resolveSibling(labelled.getFileName() + ".tmp");
        Path lines = labelled.resolveSibling("requests.jsonl");
        Path pendingLines = lines.resolveSibling("requests.jsonl.tmp");
        // Stream the ledger once, so large files are not collected into a Java list.
        try (var writer = Files.newBufferedWriter(pendingLines, StandardCharsets.UTF_8);
             var generator = json.getFactory().createGenerator(pendingLabelled.toFile(),
                     com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            generator.useDefaultPrettyPrinter();
            generator.writeStartObject();
            generator.writeNumberField("previewVersion", 1);
            generator.writeStringField("mode", "PREVIEW");
            generator.writeFieldName("source");
            json.writeTree(generator, source(run, snapshot));
            generator.writeArrayFieldStart("requests");
            long after = 0;
            ProcessingLedger.Request request;
            while ((request = ledger.next(run.id(), after)) != null) {
                writer.write(request.payload());
                writer.newLine();
                json.writeTree(generator, json.readTree(request.payload()));
                after = request.number();
            }
            generator.writeEndArray();
            generator.writeArrayFieldStart("requestDetails");
            after = 0;
            while ((request = ledger.next(run.id(), after)) != null) {
                generator.writeStartObject();
                generator.writeNumberField("requestNumber", request.number());
                var origins = request.sourceRecords() == null ? json.createArrayNode().add(request.sourceRecord())
                        : json.readTree(request.sourceRecords());
                if (origins.size() == 1) generator.writeNumberField("sourceRecordNumber", request.sourceRecord());
                else { generator.writeFieldName("sourceRecordNumbers"); json.writeTree(generator,origins); }
                generator.writeEndObject();
                after = request.number();
            }
            generator.writeEndArray();
            generator.writeEndObject();
        }
        FileProcessor.atomicMove(pendingLines, lines);
        FileProcessor.atomicMove(pendingLabelled, labelled);
        return labelled;
    }

    private ObjectNode source(ProcessingLedger.Run run, RunSnapshot snapshot) {
        var layout = snapshot.profile().layout();
        var source = json.createObjectNode();
        source.put("fileName", run.filename());
        source.put("fileType", fileType(layout));
        source.put("format", layout.format().name());
        source.put("charset", layout.charset());
        source.put("profileId", snapshot.profile().id());
        source.put("runId", run.id());
        source.put("contentSha256", run.contentHash());
        source.put("requestCount", ledger.count(run.id()));
        source.put("engineVersion", snapshot.engineVersion());
        source.put("projectionVersion", snapshot.profile().projection().version());
        source.put("projectionScope", snapshot.profile().projection().scope().name());
        source.put("requestBodyShape", "CONFIGURED_PROJECTION");
        if (layout.format() == Profile.Format.DELIMITED) {
            source.put("delimiter", layout.delimiter());
            source.put("headerMode", layout.headerMode().name());
        } else if (layout.format() == Profile.Format.FIXED_WIDTH) {
            source.put("headerMode", "NONE");
            source.put("recordLength", LayoutValidator.recordLength(layout.fixedWidth()));
        } else if (layout.format() == Profile.Format.XML) {
            source.put("recordPath", layout.xml().recordPath());
        } else if (layout.format() == Profile.Format.JSON) {
            source.put("jsonMode", layout.json().mode().name());
        }
        return source;
    }

    private String fileType(Profile.Layout layout) {
        if (layout.format() != Profile.Format.DELIMITED) return layout.format().name();
        return switch (layout.delimiter()) {
            case "," -> "CSV";
            case "|" -> "PIPE_DELIMITED";
            case "\t" -> "TAB_DELIMITED";
            default -> "DELIMITED";
        };
    }
}
