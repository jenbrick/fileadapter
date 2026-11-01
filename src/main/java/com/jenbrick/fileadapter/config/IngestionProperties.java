package com.jenbrick.fileadapter.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * These are the settings loaded from YAML: where files live, how to process them, and where to send
 * the JSON.
 */
@ConfigurationProperties("ingestion")
public record IngestionProperties(
        Path workDirectory, Path inputDirectory, Mode mode,
        boolean pollEnabled, Duration minimumAge, String readySuffix,
        Path file, String profileId, String resume,
        Delivery delivery, List<Profile> profiles) {

    // PREVIEW writes request files. LIVE can send those requests to the configured endpoint.
    public enum Mode { PREVIEW, LIVE }

    /** No credentials belong in the persisted delivery snapshot. */
    // These delivery settings are saved with the run; bearer tokens are supplied separately at runtime.
    public record Delivery(URI endpoint, Duration connectTimeout,
                           Duration readTimeout, int maxAttempts,
                           Duration retryDelay, boolean replaySafe,
                           String idempotencyHeader, List<Integer> successStatuses, String responseContract) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding public Delivery {}
        // Status-only callers can leave out the optional response protocol.
        public Delivery(URI endpoint,Duration connectTimeout,Duration readTimeout,int maxAttempts,
                        Duration retryDelay,boolean replaySafe,String idempotencyHeader,List<Integer> successStatuses) {
            this(endpoint,connectTimeout,readTimeout,maxAttempts,retryDelay,replaySafe,idempotencyHeader,successStatuses,null);
        }
    }
}
