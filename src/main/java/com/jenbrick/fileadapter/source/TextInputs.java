package com.jenbrick.fileadapter.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import org.apache.commons.io.input.BOMInputStream;

final class TextInputs {
    private TextInputs() {}

    static BufferedReader open(Path file, String charset) throws IOException {
        var input = BOMInputStream.builder().setPath(file).get();
        try {
            return new BufferedReader(new InputStreamReader(input, Charset.forName(charset).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)));
        } catch (RuntimeException e) {
            input.close();
            throw e;
        }
    }

    /** Handles LF, CRLF, and CR without allocating an unbounded line. */
    static String readLine(BufferedReader reader, int limit) throws IOException {
        var line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') return line.toString();
            if (value == '\r') {
                reader.mark(1);
                if (reader.read() != '\n') reader.reset();
                return line.toString();
            }
            if (line.length() >= limit) throw new RecordFailure("Input line exceeds maximum supported length");
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }
}
