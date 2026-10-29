package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.source.SourceRecord;
import com.jenbrick.fileadapter.source.RecordFailure;
import org.springframework.stereotype.Component;

@Component
public class ConfigurableRequestMapper implements RequestMapper {
    private final MappingValues values;

    public ConfigurableRequestMapper(TransformRegistry transforms) { this.values = new MappingValues(transforms); }

    @Override
    public ObjectNode map(SourceRecord source, Profile profile) {
        ObjectNode target = JsonNodeFactory.instance.objectNode();
        for (var field : profile.mappings()) {
            try {
                JsonNode value = values.resolve(source.payload(), field);
                if (value.isMissingNode()) continue;
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
