package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Add a named value conversion here when the built-in conversions are not enough.
 */
public interface ValueTransform {
    String name();
    JsonNode apply(JsonNode value);
}
