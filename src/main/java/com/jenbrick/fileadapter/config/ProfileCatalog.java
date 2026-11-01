package com.jenbrick.fileadapter.config;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import org.springframework.stereotype.Component;

/**
 * Pick the one profile that applies to this file, and check the configured profiles when the
 * application starts.
 */
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
        // Check every configured feed now so a broken profile does not wait until its first file
        // arrives.
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
            if(profile.errors()!=null) {
                profile.errors().validate();
                if(profile.errors().projectionSeverity()==com.jenbrick.fileadapter.results.ErrorPolicy.Severity.MEDIUM
                        && profile.projection().scope()!=com.jenbrick.fileadapter.projection.Projection.Scope.GROUPED)
                    throw new IllegalArgumentException("MEDIUM projection handling requires GROUPED scope");
            }
        }
    }

    public Profile resolve(Path file, String explicitId) {
        // An explicit profile ID takes priority; otherwise match the filename against the
        // configured patterns.
        var matches = properties.profiles().stream()
                .filter(p -> explicitId != null ? p.id().equals(explicitId)
                        : matcher(p.filenamePattern()).matches(file.getFileName()))
                .toList();
        // Picking the first match would hide overlapping profiles, so require exactly one.
        if (matches.size() != 1) {
            throw new IllegalArgumentException("Expected exactly one matching profile; found " + matches.size());
        }
        return matches.get(0);
    }

    private static java.nio.file.PathMatcher matcher(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }
}
