package com.jenbrick.fileadapter.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.Profile;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;

/**
 * Translate physical input names into the logical field names used by lookup and mapping.
 */
@Component
public class SourceNormalizer {
    public SourceRecord normalize(SourceRecord source, Profile.Layout layout) {
        // With no renaming configured, the original field names are already the logical names.
        if (layout.fields() == null || layout.fields().isEmpty()) return source;
        // A configured field list is the complete logical record, so include every field later
        // steps need.
        ObjectNode logical = JsonNodeFactory.instance.objectNode();
        layout.fields().forEach((name, field) -> {
            var candidates = new LinkedHashSet<String>();
            candidates.add(field.column());
            if (field.aliases() != null) candidates.addAll(field.aliases());
            JsonNode value = null;
            for (String candidate : candidates) {
                JsonNode found = candidate.startsWith("/")
                        ? source.payload().at(candidate) : source.payload().get(candidate);
                if (found == null || found.isMissingNode()) continue;
                // Two aliases with different values are ambiguous; do not choose one based on their
                // order.
                if (value != null && !value.equals(found)) {
                    throw new RecordFailure("Record " + source.number() + ": conflicting source aliases for " + name);
                }
                value = found;
            }
            if (value != null) logical.set(name, value);
        });
        return new SourceRecord(source.number(), logical);
    }
}
