package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.source.RecordFailure;
import org.springframework.stereotype.Component;

/**
 * Check the configured required fields and JSON types. This is a small set of checks, not a full
 * API schema.
 */
@Component
public class RequestValidator {
    public void validateFields(JsonNode request, java.util.List<Profile.ContractField> rules) {
        // Only enforce checks that the profile declares; different JSON shapes can declare
        // different checks.
        if (rules == null) return;
        for (var rule : rules) {
            JsonNode value = JsonPaths.get(request, rule.path());
            if (value.isMissingNode()) {
                if (rule.required()) fail(rule.path(), "is missing");
                continue;
            }
            // A field can be present and still be null, so required and nullable are separate rules.
            if (value.isNull()) {
                if (!rule.nullable()) fail(rule.path(), "cannot be null");
                continue;
            }
            boolean valid = switch (rule.type()) {
                case STRING -> value.isTextual();
                case INTEGER -> value.isIntegralNumber();
                case DECIMAL -> value.isNumber();
                case BOOLEAN -> value.isBoolean();
                case OBJECT -> value.isObject();
                case ARRAY -> value.isArray();
            };
            if (!valid) fail(rule.path(), "has wrong type; expected " + rule.type());
        }
    }

    private void fail(String path, String reason) {
        throw new RecordFailure("Output field " + path + " " + reason);
    }
}
