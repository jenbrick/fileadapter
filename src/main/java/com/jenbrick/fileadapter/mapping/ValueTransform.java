package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;

/** Register a Spring bean to add a named, testable business conversion. */
public interface ValueTransform {
    String name();
    JsonNode apply(JsonNode value);
}
