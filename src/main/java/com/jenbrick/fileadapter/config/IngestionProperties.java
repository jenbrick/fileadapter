package com.jenbrick.fileadapter.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ingestion")
public record IngestionProperties(
        Path workDirectory, Path inputDirectory, Mode mode,
        boolean pollEnabled, Duration minimumAge, String readySuffix,
        Path file, String profileId, String resume,
        Delivery delivery, List<Profile> profiles) {

    public enum Mode { PREVIEW, LIVE }

    /** No credentials belong in the persisted delivery snapshot. */
    public record Delivery(URI endpoint, Duration connectTimeout,
                           Duration readTimeout, int maxAttempts,
                           Duration retryDelay, boolean replaySafe,
                           String idempotencyHeader, List<Integer> successStatuses) {}
}
