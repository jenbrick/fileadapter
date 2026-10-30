package com.jenbrick.fileadapter.config;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import org.springframework.stereotype.Component;

@Component
public class ProfileCatalog {
    private final IngestionProperties properties;

    public ProfileCatalog(IngestionProperties properties,
                          com.jenbrick.fileadapter.projection.ProjectionEngine projections) {
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
            LayoutValidator.validate(profile.layout());
            if (profile.projection() == null) {
                throw new IllegalArgumentException("Every profile requires a projection");
            }
            try { projections.validate(profile.projection()); }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid projection " + profile.id() + ": " + e.getMessage(), e);
            }
            projections.validateEnrichers(profile);
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
