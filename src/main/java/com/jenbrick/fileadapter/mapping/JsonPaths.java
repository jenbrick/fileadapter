package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deliberately supports object paths only; array assembly belongs in an extension. */
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

    public static void put(ObjectNode root, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = parent.get(parts[i]);
            if (child == null) child = parent.putObject(parts[i]);
            if (!(child instanceof ObjectNode object)) {
                throw new IllegalArgumentException("Conflicting target path: " + path);
            }
            parent = object;
        }
        if (parent.has(parts[parts.length - 1])) {
            throw new IllegalArgumentException("Duplicate target path: " + path);
        }
        parent.set(parts[parts.length - 1], value.deepCopy());
    }
}
