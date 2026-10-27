package com.jenbrick.fileadapter.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;

/** XML selectors use absolute element paths, not general XPath expressions. */
public final class XmlNames {
    private XmlNames() {}

    public static List<QName> recordPath(Profile.Xml options) {
        if (options == null || options.recordPath() == null || !options.recordPath().startsWith("/")) {
            throw new IllegalArgumentException("XML requires an absolute record-path");
        }
        Map<String, String> namespaces = options.namespaces() == null ? Map.of() : options.namespaces();
        var uris = new java.util.HashSet<String>();
        namespaces.forEach((prefix, uri) -> {
            if (!prefix.matches("[A-Za-z_][A-Za-z0-9_.-]*") || prefix.equals("xmlns")
                    || uri == null || uri.isBlank() || !uris.add(uri)
                    || (prefix.equals("xml") && !uri.equals(XMLConstants.XML_NS_URI))) {
                throw new IllegalArgumentException("XML namespace aliases must be unique and bound to a URI");
            }
        });
        var path = new ArrayList<QName>();
        for (String segment : options.recordPath().substring(1).split("/", -1)) {
            if (!segment.matches("[A-Za-z_][A-Za-z0-9_.-]*(:[A-Za-z_][A-Za-z0-9_.-]*)?")) {
                throw new IllegalArgumentException("XML record-path accepts element names only");
            }
            String[] parts = segment.split(":", 2);
            if (parts.length == 1) path.add(new QName("", parts[0]));
            else {
                String uri = parts[0].equals("xml") ? XMLConstants.XML_NS_URI : namespaces.get(parts[0]);
                if (uri == null) throw new IllegalArgumentException("Unbound prefix in XML record-path");
                path.add(new QName(uri, parts[1]));
            }
        }
        return List.copyOf(path);
    }

    public static String key(QName name, Profile.Xml options) {
        if (name.getNamespaceURI().isEmpty()) return name.getLocalPart();
        if (name.getNamespaceURI().equals(XMLConstants.XML_NS_URI)) return "xml:" + name.getLocalPart();
        if (options.namespaces() != null) {
            for (var binding : options.namespaces().entrySet()) {
                if (binding.getValue().equals(name.getNamespaceURI())) {
                    return binding.getKey() + ":" + name.getLocalPart();
                }
            }
        }
        return "{" + name.getNamespaceURI() + "}" + name.getLocalPart();
    }
}
