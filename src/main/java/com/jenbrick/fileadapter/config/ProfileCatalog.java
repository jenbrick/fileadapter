package com.jenbrick.fileadapter.config;

import com.jenbrick.fileadapter.mapping.JsonPaths;
import com.jenbrick.fileadapter.mapping.TransformRegistry;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import org.springframework.stereotype.Component;

@Component
public class ProfileCatalog {
    private final IngestionProperties properties;

    public ProfileCatalog(IngestionProperties properties, TransformRegistry transforms) {
        this.properties = properties;
        var ids = new HashSet<String>();
        if (properties.profiles() == null || properties.profiles().isEmpty()) {
            throw new IllegalArgumentException("At least one ingestion profile is required");
        }
        for (var profile : properties.profiles()) {
            if (profile.id() == null || !profile.id().matches("[A-Za-z0-9_-]+") || !ids.add(profile.id())) {
                throw new IllegalArgumentException("Profile IDs must be unique simple identifiers");
            }
            matcher(profile.filenamePattern());
            if (profile.layout() == null || profile.layout().format() == null) {
                throw new IllegalArgumentException("Profile requires a layout and format");
            }
            var layout = profile.layout();
            Charset.forName(layout.charset());
            if (layout.format() == Profile.Format.DELIMITED) {
                if (layout.delimiter() == null || layout.delimiter().length() != 1
                        || "\r\n".contains(layout.delimiter())) {
                    throw new IllegalArgumentException("Delimited layout requires one valid delimiter");
                }
                if (layout.headerMode() == null) throw new IllegalArgumentException("header-mode is required");
                if (layout.headerMode() == Profile.HeaderMode.NONE
                        && (layout.columns() == null || layout.columns().isEmpty()
                        || layout.columns().stream().anyMatch(s -> s == null || s.isBlank())
                        || new HashSet<>(layout.columns()).size() != layout.columns().size())) {
                    throw new IllegalArgumentException("Headerless layouts require unique named columns");
                }
            }
            if (layout.fields() != null) layout.fields().forEach((name, field) -> {
                if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*")
                        || field.column() == null || field.column().isBlank()) {
                    throw new IllegalArgumentException("Logical fields require a name and source column");
                }
            });
            if (profile.mappings() == null || profile.mappings().isEmpty() || profile.contract() == null) {
                throw new IllegalArgumentException("Profile requires mappings and an output contract");
            }
            var paths = new HashSet<String>();
            for (var field : profile.mappings()) {
                JsonPaths.check(field.source());
                JsonPaths.check(field.target());
                if (!paths.add(field.target())) throw new IllegalArgumentException("Duplicate target path");
                if (field.transforms() != null) field.transforms().forEach(transforms::require);
            }
            for (String path : paths) for (String other : paths) {
                if (other.startsWith(path + ".")) throw new IllegalArgumentException("Overlapping target paths");
            }
            profile.contract().forEach(rule -> {
                JsonPaths.check(rule.path());
                if (rule.type() == null) throw new IllegalArgumentException("Contract field type is required");
            });
        }
    }

    public Profile resolve(Path file, String explicitId) {
        var matches = properties.profiles().stream()
                .filter(p -> explicitId != null ? p.id().equals(explicitId)
                        : matcher(p.filenamePattern()).matches(file.getFileName()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one matching profile; found " + matches.size());
        }
        return matches.get(0);
    }

    private static java.nio.file.PathMatcher matcher(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }
}
