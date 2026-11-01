package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jenbrick.fileadapter.config.Profile;
import org.springframework.stereotype.Component;

/**
 * Resolve one mapped value, then apply its conversions, defaults, blank handling, and code
 * translations.
 */
@Component
public class MappingValues {
    private final TransformRegistry transforms;
    public MappingValues(TransformRegistry transforms) { this.transforms = transforms; }
    public JsonNode resolve(JsonNode source, Profile.ScalarMapping field) {
        JsonNode value = JsonPaths.get(source, field.source());
        // A default value fills in an absent field. A present blank has its own handling below.
        if (value.isMissingNode() && field.defaultValue() != null) value = TextNode.valueOf(field.defaultValue());
        if (value.isMissingNode()) {
            if (field.required()) throw new IllegalArgumentException("Required source is missing");
            return value;
        }
        // Apply conversions in the order listed in YAML.
        if (!value.isNull() && field.transforms() != null) {
            for (String transform : field.transforms()) value = transforms.apply(transform, value);
        }
        // Decide whether a blank should be omitted, kept empty, turned into null, or rejected.
        if (value.isTextual() && value.asText().isBlank()) {
            switch (field.blankPolicy() == null ? Profile.BlankPolicy.OMIT : field.blankPolicy()) {
                case REJECT -> throw new IllegalArgumentException("Blank value");
                case NULL -> value = NullNode.getInstance();
                case EMPTY -> value = TextNode.valueOf("");
                case OMIT -> {
                    if (field.required()) throw new IllegalArgumentException("Required value is blank");
                    return MissingNode.getInstance();
                }
            }
        }
        if (field.required() && (value.isNull() || (value.isTextual() && value.asText().isBlank()))) {
            throw new IllegalArgumentException("Required value is null or blank");
        }
        // Translate allowed business codes explicitly. An unknown code should not pass through
        // unnoticed.
        if (!value.isNull() && field.values() != null && !field.values().isEmpty()) {
            String translated = field.values().get(value.asText());
            if (translated == null) throw new IllegalArgumentException("Unmapped code");
            value = TextNode.valueOf(translated);
        }
        return value;
    }
}
