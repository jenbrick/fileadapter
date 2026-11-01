package com.jenbrick.fileadapter.source;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jenbrick.fileadapter.config.LayoutValidator;
import com.jenbrick.fileadapter.config.Profile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Read fields by their configured positions while keeping the full original record for customer
 * returns.
 */
@Component
public class FixedWidthFileAdapter implements FileFormatAdapter {
    @Override public Profile.Format format() { return Profile.Format.FIXED_WIDTH; }

    @Override
    public void read(Path file, Profile.Layout layout, Consumer<SourceRecord> consumer) throws IOException {
        LayoutValidator.validate(layout);
        int width = LayoutValidator.recordLength(layout.fixedWidth());
        try (var reader = TextInputs.open(file, layout.charset())) {
            String line;
            long number = 0;
            while ((line = TextInputs.readLine(reader, width * 2)) != null) {
                number++;
                // Check the full record length before extracting fields by position.
                if (line.codePointCount(0, line.length()) != width) {
                    throw new RecordFailure("Fixed-width record " + number + " must contain exactly " + width
                            + " characters");
                }
                var data = JsonNodeFactory.instance.objectNode();
                for (var field : layout.fixedWidth().columns()) {
                    // Offsets count Unicode code points, matching the configured fixed-width layout.
                    int start = line.offsetByCodePoints(0, field.offset());
                    int end = line.offsetByCodePoints(start, field.length());
                    data.put(field.name(), line.substring(start, end));
                }
                // Keep the original line as well as the extracted fields for an exact fixed-width
                // prefix in customer returns.
                consumer.accept(new SourceRecord(number, data, line));
            }
        }
    }
}
