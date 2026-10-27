package com.jenbrick.fileadapter.source;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.config.LayoutValidator;
import com.jenbrick.fileadapter.config.Profile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class JsonFileAdapter implements FileFormatAdapter {
    private final ObjectMapper json;

    public JsonFileAdapter(ObjectMapper mapper) {
        json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(128).maxStringLength(1_000_000).maxNumberLength(1000).build());
    }

    @Override public Profile.Format format() { return Profile.Format.JSON; }

    @Override
    public void read(Path file, Profile.Layout layout, Consumer<SourceRecord> consumer) throws IOException {
        LayoutValidator.validate(layout);
        try (var reader = TextInputs.open(file, layout.charset())) {
            if (layout.json().mode() == Profile.JsonMode.LINES) {
                String line;
                long number = 0;
                while ((line = TextInputs.readLine(reader, 1_000_000)) != null) {
                    if (line.isBlank()) continue;
                    try (var parser = json.getFactory().createParser(line)) {
                        parser.nextToken();
                        consumer.accept(new SourceRecord(++number, readObject(parser)));
                        requireEnd(parser);
                    }
                }
            } else {
                try (var parser = json.getFactory().createParser(reader)) {
                    if (layout.json().mode() == Profile.JsonMode.ARRAY) {
                        if (parser.nextToken() != JsonToken.START_ARRAY) {
                            throw new RecordFailure("JSON ARRAY mode requires a root array");
                        }
                        long number = 0;
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            consumer.accept(new SourceRecord(++number, readObject(parser)));
                        }
                    } else {
                        parser.nextToken();
                        consumer.accept(new SourceRecord(1, readObject(parser)));
                    }
                    requireEnd(parser);
                }
            }
        }
    }

    private JsonNode readObject(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw new RecordFailure("Every JSON record must be an object");
        }
        return json.readTree(parser);
    }

    private void requireEnd(JsonParser parser) throws IOException {
        if (parser.nextToken() != null) throw new RecordFailure("Unexpected data after JSON document");
    }
}
