package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/** Dotted source and validation paths; collection assembly belongs to ProjectionEngine. */
public final class JsonPaths {
    private JsonPaths() {}

    public static void check(String path) {
        if (path == null || !path.matches("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*")) {
            throw new IllegalArgumentException("Invalid object path: " + path);
        }
    }

    public static JsonNode get(JsonNode root, String path) {
        JsonNode value = root;
        for (String part : path.split("\\.")) {
            value = value.get(part);
            if (value == null) return MissingNode.getInstance();
        }
        return value;
    }

}
