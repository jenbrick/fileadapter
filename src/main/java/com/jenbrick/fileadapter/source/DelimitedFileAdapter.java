package com.jenbrick.fileadapter.source;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.Profile;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.apache.commons.io.input.BOMInputStream;
import org.springframework.stereotype.Component;

/**
 * Read CSV or another single-character delimiter and turn each data record into named source fields.
 */
@Component
public class DelimitedFileAdapter implements FileFormatAdapter {
    @Override public Profile.Format format() { return Profile.Format.DELIMITED; }

    @Override
    public void read(Path file, Profile.Layout layout, Consumer<SourceRecord> consumer)
            throws IOException {
        // Keep standard CSV quoting and escaping while letting the profile choose the delimiter.
        var builder = CSVFormat.RFC4180.builder()
                .setDelimiter(layout.delimiter())
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(false)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW);
        // Use either the file's first record as headers or the complete configured column list.
        if (layout.headerMode() == Profile.HeaderMode.FIRST_RECORD) {
            builder.setHeader().setSkipHeaderRecord(true);
        } else {
            builder.setHeader(layout.columns().toArray(String[]::new))
                    .setSkipHeaderRecord(false);
        }
        try (var input = BOMInputStream.builder().setPath(file).get();
             Reader reader = new InputStreamReader(input, Charset.forName(layout.charset()).newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT)
                     .onUnmappableCharacter(CodingErrorAction.REPORT));
             CSVParser parser = builder.get().parse(reader)) {
            if (parser.getHeaderNames().isEmpty()) {
                throw new IllegalArgumentException("The input has no header");
            }
            if (parser.getHeaderNames().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Blank header names are not allowed");
            }
            for (CSVRecord row : parser) {
                // A shifted column count can put member data in the wrong field, so reject the row.
                if (!row.isConsistent()) {
                    throw new IllegalArgumentException(
                            "Column count mismatch at record " + row.getRecordNumber());
                }
                ObjectNode data = JsonNodeFactory.instance.objectNode();
                parser.getHeaderNames().forEach(header -> data.put(header, row.get(header)));
                // Give downstream steps named source fields. Lookup and output mapping happen later.
                consumer.accept(new SourceRecord(row.getRecordNumber(), data));
            }
        }
    }
}
