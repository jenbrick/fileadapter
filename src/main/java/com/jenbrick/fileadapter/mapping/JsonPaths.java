package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Read simple paths such as resolved.owner.contract. Building arrays and grouping rows happen in
 * the projection engine.
 */
public final class JsonPaths {
    private JsonPaths() {}

    // Allow simple object paths rather than expressions or executable mapping code.
    public static void check(String path) {
        if (path == null || !path.matches("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*")) {
            throw new IllegalArgumentException("Invalid object path: " + path);
        }
    }

    public static JsonNode get(JsonNode root, String path) {
        // Return MissingNode when the path is absent so mapping can distinguish missing from
        // explicit null.
        JsonNode value = root;
        for (String part : path.split("\\.")) {
            value = value.get(part);
            if (value == null) return MissingNode.getInstance();
        }
        return value;
    }

}
