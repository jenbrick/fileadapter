package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.source.RecordFailure;
import org.springframework.stereotype.Component;

/** Small explicit contract; replace/extend with the real API's schema when provided. */
@Component
public class RequestValidator {
    public void validate(JsonNode request, Profile profile) {
        validateFields(request, profile.contract());
    }

    public void validateFields(JsonNode request, java.util.List<Profile.ContractField> rules) {
        if (rules == null) return;
        for (var rule : rules) {
            JsonNode value = JsonPaths.get(request, rule.path());
            if (value.isMissingNode()) {
                if (rule.required()) fail(rule.path(), "is missing");
                continue;
            }
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
