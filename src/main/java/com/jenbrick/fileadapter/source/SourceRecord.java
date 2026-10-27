package com.jenbrick.fileadapter.source;

import com.fasterxml.jackson.databind.JsonNode;

/** Record number is a logical record, not a physical line (CSV can be multiline). */
public record SourceRecord(long number, JsonNode payload) {}
