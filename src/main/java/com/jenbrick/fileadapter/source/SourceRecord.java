package com.jenbrick.fileadapter.source;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Carry a logical record number and its source fields. Fixed-width records also keep their original
 * text, including gaps and padding.
 */
public record SourceRecord(long number, JsonNode payload, String rawText) {
    public SourceRecord(long number,JsonNode payload) { this(number,payload,null); }
}
