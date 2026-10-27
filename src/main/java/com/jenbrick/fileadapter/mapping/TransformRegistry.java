package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

@Component
public class TransformRegistry {
    private final Map<String, UnaryOperator<JsonNode>> transforms = new HashMap<>();
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    public TransformRegistry(List<ValueTransform> extensions) {
        transforms.put("trim", v -> nodes.textNode(v.asText().trim()));
        transforms.put("uppercase", v -> nodes.textNode(v.asText().toUpperCase(Locale.ROOT)));
        transforms.put("lowercase", v -> nodes.textNode(v.asText().toLowerCase(Locale.ROOT)));
        transforms.put("integer", v -> nodes.numberNode(new BigInteger(v.asText())));
        transforms.put("decimal", v -> nodes.numberNode(new BigDecimal(v.asText())));
        transforms.put("boolean", v -> {
            String text = v.asText();
            if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Expected true or false");
            }
            return nodes.booleanNode(Boolean.parseBoolean(text));
        });
        transforms.put("iso-date", v -> nodes.textNode(LocalDate.parse(v.asText()).toString()));
        transforms.put("us-date", v -> nodes.textNode(LocalDate.parse(v.asText(),
                DateTimeFormatter.ofPattern("MM/dd/uuuu")
                        .withResolverStyle(ResolverStyle.STRICT)).toString()));
        for (var extension : extensions) {
            if (transforms.putIfAbsent(extension.name(), extension::apply) != null) {
                throw new IllegalArgumentException("Duplicate transform: " + extension.name());
            }
        }
    }

    public void require(String name) {
        if (!transforms.containsKey(name)) throw new IllegalArgumentException("Unknown transform: " + name);
    }

    public JsonNode apply(String name, JsonNode value) {
        require(name);
        return transforms.get(name).apply(value);
    }
}
