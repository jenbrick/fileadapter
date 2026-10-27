package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.source.SourceRecord;
import com.jenbrick.fileadapter.source.RecordFailure;
import org.springframework.stereotype.Component;

@Component
public class ConfigurableRequestMapper implements RequestMapper {
    private final TransformRegistry transforms;

    public ConfigurableRequestMapper(TransformRegistry transforms) { this.transforms = transforms; }

    @Override
    public ObjectNode map(SourceRecord source, Profile profile) {
        ObjectNode target = JsonNodeFactory.instance.objectNode();
        for (var field : profile.mappings()) {
            try {
                JsonNode value = JsonPaths.get(source.payload(), field.source());
                if (value.isMissingNode() && field.defaultValue() != null) {
                    value = TextNode.valueOf(field.defaultValue());
                }
                if (value.isMissingNode()) {
                    if (field.required()) throw new IllegalArgumentException("Required source field is missing");
                    continue;
                }
                if (!value.isNull() && field.transforms() != null) {
                    for (String transform : field.transforms()) value = transforms.apply(transform, value);
                }
                if (value.isTextual() && value.asText().isBlank()) {
                    var policy = field.blankPolicy() == null ? Profile.BlankPolicy.OMIT : field.blankPolicy();
                    switch (policy) {
                        case REJECT -> throw new IllegalArgumentException("Blank value is not allowed");
                        case NULL -> value = NullNode.getInstance();
                        case EMPTY -> value = TextNode.valueOf("");
                        case OMIT -> {
                            if (field.required()) throw new IllegalArgumentException("Required value is blank");
                            continue;
                        }
                    }
                }
                if (field.required() && (value.isNull() || (value.isTextual() && value.asText().isBlank()))) {
                    throw new IllegalArgumentException("Required value is null or blank");
                }
                if (!value.isNull() && field.values() != null && !field.values().isEmpty()) {
                    String translated = field.values().get(value.asText());
                    if (translated == null) throw new IllegalArgumentException("Value has no configured translation");
                    value = TextNode.valueOf(translated);
                }
                JsonPaths.put(target, field.target(), value);
            } catch (RuntimeException e) {
                // Do not include source values: they may contain personal information.
                throw new RecordFailure("Record " + source.number()
                        + ", mapping " + field.source() + " -> " + field.target()
                        + " failed (" + e.getClass().getSimpleName() + ")");
            }
        }
        return target;
    }
}
