package com.jenbrick.fileadapter.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jenbrick.fileadapter.config.LayoutValidator;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.config.XmlNames;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

@Component
public class XmlFileAdapter implements FileFormatAdapter {
    private static final int MAX_DEPTH = 128;
    private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    @Override public Profile.Format format() { return Profile.Format.XML; }

    @Override
    public void read(Path file, Profile.Layout layout, Consumer<SourceRecord> consumer) throws IOException {
        LayoutValidator.validate(layout);
        var selector = XmlNames.recordPath(layout.xml());
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External XML resources are disabled");
        });
        try (var input = TextInputs.open(file, layout.charset())) {
            XMLStreamReader xml = factory.createXMLStreamReader(input);
            try {
                String declared = xml.getCharacterEncodingScheme();
                if (declared != null && !Charset.forName(declared).equals(Charset.forName(layout.charset()))) {
                    throw new RecordFailure("XML declaration encoding differs from configured charset");
                }
                var path = new ArrayList<QName>();
                long number = 0;
                while (xml.hasNext()) {
                    int event = xml.next();
                    rejectEntities(event);
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        path.add(xml.getName());
                        if (path.size() > MAX_DEPTH) throw new RecordFailure("XML nesting exceeds 128 levels");
                        if (path.equals(selector)) {
                            JsonNode record = element(xml, layout.xml(), path.size(), new Budget());
                            if (!record.isObject()) {
                                throw new RecordFailure("Selected XML record must contain fields or attributes");
                            }
                            consumer.accept(new SourceRecord(++number, record));
                            path.remove(path.size() - 1); // element() consumed this record's end tag.
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        path.remove(path.size() - 1);
                    }
                }
            } finally {
                xml.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("Malformed or unsupported XML", e);
        }
    }

    /** Reads only one selected record into memory, preserving repeated children as arrays. */
    private JsonNode element(XMLStreamReader xml, Profile.Xml options, int depth, Budget budget)
            throws XMLStreamException {
        if (depth > MAX_DEPTH) throw new RecordFailure("XML nesting exceeds 128 levels");
        budget.node();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        boolean nil = false;
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            QName name = xml.getAttributeName(i);
            String value = xml.getAttributeValue(i);
            budget.node();
            budget.text(value.length());
            if (name.equals(new QName(XSI, "nil"))) {
                if (!java.util.Set.of("true", "false", "1", "0").contains(value)) {
                    throw new RecordFailure("Invalid xsi:nil attribute");
                }
                nil = value.equals("true") || value.equals("1");
            } else {
                result.put("@" + XmlNames.key(name, options), value);
            }
        }
        var text = new StringBuilder();
        boolean children = false;
        while (xml.hasNext()) {
            int event = xml.next();
            rejectEntities(event);
            if (event == XMLStreamConstants.START_ELEMENT) {
                children = true;
                String key = XmlNames.key(xml.getName(), options);
                JsonNode value = element(xml, options, depth + 1, budget);
                JsonNode previous = result.get(key);
                if (previous == null) result.set(key, value);
                else if (previous instanceof ArrayNode array) array.add(value);
                else result.set(key, JsonNodeFactory.instance.arrayNode().add(previous).add(value));
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE) {
                budget.text(xml.getTextLength());
                text.append(xml.getTextCharacters(), xml.getTextStart(), xml.getTextLength());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (nil) {
                    if (children || !text.toString().isBlank()) {
                        throw new RecordFailure("An xsi:nil element cannot contain a value");
                    }
                    return NullNode.getInstance();
                }
                if (children && !text.toString().isBlank()) {
                    throw new RecordFailure("Mixed XML text and child elements require a custom adapter");
                }
                if (result.isEmpty()) return TextNode.valueOf(text.toString());
                if (!children && !text.isEmpty()) result.put("#text", text.toString());
                return result;
            }
        }
        throw new XMLStreamException("Unclosed XML element");
    }

    private static void rejectEntities(int event) {
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new RecordFailure("XML DTDs and custom entity references are disabled");
        }
    }

    private static final class Budget {
        int nodes;
        int characters;
        void node() {
            if (++nodes > 100_000) throw new RecordFailure("XML record exceeds 100000 nodes");
        }
        void text(int count) {
            characters += count;
            if (characters > 1_000_000) throw new RecordFailure("XML record exceeds 1000000 text characters");
        }
    }
}
