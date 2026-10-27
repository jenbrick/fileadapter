package com.jenbrick.fileadapter.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.config.IngestionProperties;
import com.jenbrick.fileadapter.config.ProfileCatalog;
import com.jenbrick.fileadapter.delivery.UpsertClient;
import com.jenbrick.fileadapter.mapping.RequestMapper;
import com.jenbrick.fileadapter.mapping.RequestValidator;
import com.jenbrick.fileadapter.source.AdapterRegistry;
import com.jenbrick.fileadapter.source.SourceNormalizer;
import com.jenbrick.fileadapter.source.RecordFailure;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class FileProcessor {
    private static final Logger log = LoggerFactory.getLogger(FileProcessor.class);
    private final IngestionProperties properties;
    private final ProfileCatalog profiles;
    private final AdapterRegistry adapters;
    private final SourceNormalizer normalizer;
    private final RequestMapper mapper;
    private final RequestValidator validator;
    private final com.jenbrick.fileadapter.consent.ConsentRequestPlanner consentPlanner;
    private final UpsertClient client;
    private final ProcessingLedger ledger;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final PreviewWriter previews;
    private final Path work;

    public FileProcessor(IngestionProperties properties, ProfileCatalog profiles,
                         AdapterRegistry adapters, SourceNormalizer normalizer,
                         RequestMapper mapper, RequestValidator validator,
                         UpsertClient client, ProcessingLedger ledger, ObjectMapper json,
                         MeterRegistry metrics, PreviewWriter previews,
                         com.jenbrick.fileadapter.consent.ConsentRequestPlanner consentPlanner) {
        this.properties = properties;
        this.profiles = profiles;
        this.adapters = adapters;
        this.normalizer = normalizer;
        this.mapper = mapper;
        this.validator = validator;
        this.client = client;
        this.ledger = ledger;
        this.json = json;
        this.metrics = metrics;
        this.previews = previews;
        this.consentPlanner = consentPlanner;
        this.work = properties.workDirectory().toAbsolutePath().normalize();
        validateDelivery(properties.delivery());
    }

    /** CLI inputs are copied; poller inputs are removed only after durable staging. */
    public synchronized String process(Path input, String profileId, boolean consume) throws IOException {
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Input must be a regular file, not a symbolic link");
        }
        var profile = profiles.resolve(input, profileId);
        var snapshot = new RunSnapshot(profile.consent() == null ? 1 : 3, profile, properties.mode(), properties.delivery());
        String config = json.writeValueAsString(snapshot);
        Files.createDirectories(work.resolve("processing"));
        Path temporary = Files.createTempFile(work.resolve("processing"), "staging-", ".tmp");
        String id;
        try {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            String contentHash = hash(temporary);
            if (consume && !hash(input).equals(contentHash)) {
                throw new IllegalStateException("Input changed after being marked ready");
            }
            id = hash((contentHash + "\n" + config).getBytes(StandardCharsets.UTF_8));
            if (ledger.get(id) == null) {
                Path directory = work.resolve("processing").resolve(id);
                Files.createDirectories(directory);
                atomicMove(temporary, directory.resolve("input"));
                Files.writeString(directory.resolve("snapshot.json"), config, StandardCharsets.UTF_8);
                ledger.create(id, input.getFileName().toString(), contentHash, config);
            }
            if (consume) {
                // A producer must not modify a file after publishing its ready marker.
                // Remove marker first: a crash cannot leave a stale marker for a new file.
                Files.deleteIfExists(Path.of(input + properties.readySuffix()));
                Files.delete(input);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        execute(id, false);
        return id;
    }

    public synchronized boolean resume(String id) throws IOException {
        return execute(id, true);
    }

    public synchronized void recover() throws IOException {
        for (String id : ledger.recoverable()) {
            try {
                execute(id, false);
            } catch (Exception e) {
                log.error("Recovery for run {} failed ({}); other runs will continue",
                        id, e.getClass().getSimpleName());
            }
        }
    }

    /** Upgrade old preview artifacts from saved payloads, without reparsing or POSTing. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void refreshPreviews() {
        for (String id : ledger.previewedRuns()) {
            var run = ledger.get(id);
            if (previews.exists(run)) continue;
            try {
                RunSnapshot snapshot = json.readValue(run.snapshot(), RunSnapshot.class);
                previews.write(run, snapshot);
            } catch (Exception e) {
                log.warn("Preview refresh for run {} failed ({})", id, e.getClass().getSimpleName());
            }
        }
    }

    private boolean execute(String id, boolean manual) throws IOException {
        var run = ledger.get(id);
        if (run == null) throw new IllegalArgumentException("Unknown run ID");
        if (run.status().equals("COMPLETED")) return true;
        if (run.status().equals("PREVIEWED")) {
            if (!previews.exists(run)) {
                previews.write(run, json.readValue(run.snapshot(), RunSnapshot.class));
            }
            return true;
        }
        if (run.status().equals("FAILED")) return false;
        if (run.status().equals("PAUSED") && !manual) return false;
        RunSnapshot snapshot = json.readValue(run.snapshot(), RunSnapshot.class);
        if (snapshot.engineVersion() < 1 || snapshot.engineVersion() > 3) throw new IllegalStateException("Unsupported mapping engine version");
        if (run.status().equals("FINALIZING")) return finish(run, snapshot);
        if (snapshot.engineVersion() == 2 && snapshot.profile().consent() != null
                && snapshot.mode() == IngestionProperties.Mode.LIVE) {
            pause(id, 0, "Legacy consent object payloads require reconciliation and a new array run");
            return false;
        }

        if (run.status().equals("STAGING")) {
            long[] currentRecord = {0};
            long[] requestNumber = {0};
            try {
                Path input = work.resolve("processing").resolve(id).resolve("input");
                if (!hash(input).equals(run.contentHash())) {
                    throw new IllegalStateException("Staged input checksum mismatch");
                }
                ledger.clearRequests(id); // STAGING never sends requests; safe after interruption.
                adapters.require(snapshot.profile().layout().format()).read(input,
                        snapshot.profile().layout(), source -> {
                            if (source.number() <= currentRecord[0]) {
                                throw new IllegalArgumentException("Adapter record numbers must increase from one");
                            }
                            currentRecord[0] = source.number();
                            var normalized = normalizer.normalize(source, snapshot.profile().layout());
                            var requests = snapshot.profile().consent() == null
                                    ? java.util.List.of(mapper.map(normalized, snapshot.profile()))
                                    : consentPlanner.plan(id, normalized, snapshot.profile().consent(), snapshot.mode(),
                                            snapshot.engineVersion());
                            java.util.List<? extends com.fasterxml.jackson.databind.JsonNode> requestsToStage = requests;
                            if (snapshot.profile().consent() != null && snapshot.engineVersion() >= 3) {
                                requestsToStage = requests.isEmpty() ? java.util.List.of()
                                        : java.util.List.of(json.valueToTree(requests));
                            }
                            for (var request : requestsToStage) {
                                if (snapshot.profile().consent() == null) validator.validate(request, snapshot.profile());
                                try {
                                    ledger.stage(id, ++requestNumber[0], source.number(), json.writeValueAsString(request));
                                } catch (IOException e) {
                                    throw new IllegalStateException("Request serialization failed", e);
                                }
                            }
                        });
                if (currentRecord[0] == 0) throw new IllegalArgumentException("No data records");
                ledger.status(id, "READY", null);
            } catch (Exception e) {
                // Parser and conversion exceptions can contain raw source data; keep reports value-free.
                fail(id, "PREFLIGHT", currentRecord[0],
                        e instanceof RecordFailure ? e.getMessage() : e.getClass().getSimpleName());
                return false;
            }
        }

        if (snapshot.mode() == IngestionProperties.Mode.PREVIEW) {
            previews.write(run, snapshot);
            ledger.status(id, "FINALIZING", null);
            return finish(run, snapshot);
        }

        ledger.status(id, "DELIVERING", null);
        long after = 0;
        ProcessingLedger.Request request;
        while ((request = ledger.next(id, after)) != null) {
            after = request.number();
            if (request.status().equals("ACKNOWLEDGED")) continue;
            if (!deliver(id, request, snapshot, manual)) return false;
        }
        ledger.status(id, "FINALIZING", null);
        return finish(run, snapshot);
    }

    private boolean deliver(String id, ProcessingLedger.Request request,
                            RunSnapshot snapshot, boolean manual) throws IOException {
        var settings = snapshot.delivery();
        if (request.status().equals("REJECTED")) {
            fail(id, "HTTP_REJECTED", request.number(), "Previously recorded permanent rejection");
            return false;
        }
        if (request.attempts() > 0 && !settings.replaySafe()) {
            pause(id, request.number(), "Prior delivery needs service-side reconciliation before replay");
            return false;
        }
        int limit = manual ? request.attempts() + settings.maxAttempts() : settings.maxAttempts();
        int attempts = request.attempts();
        while (attempts < limit) {
            ledger.sending(id, request.number()); // Persist BEFORE network side effect.
            attempts++;
            var result = client.post(json.readTree(request.payload()),
                    id + ":" + request.number(), settings);
            ledger.result(id, request.number(), result.outcome().name(), result.httpStatus());
            metrics.counter("fileadapter.requests", "outcome", result.outcome().name()).increment();
            if (result.outcome() == UpsertClient.Outcome.ACKNOWLEDGED) return true;
            if (result.outcome() == UpsertClient.Outcome.REJECTED) {
                fail(id, "HTTP_REJECTED", request.number(), "HTTP status " + result.httpStatus());
                return false;
            }
            if (!settings.replaySafe()) {
                pause(id, request.number(), "Delivery not acknowledged; automatic replay is disabled");
                return false;
            }
            if (attempts < limit) {
                try {
                    long delay = Math.min(30_000L, settings.retryDelay().toMillis()
                            * (1L << Math.min(attempts - 1, 10)));
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pause(id, request.number(), "Retry interrupted");
                    return false;
                }
            }
        }
        pause(id, request.number(), "Retry budget exhausted; explicit resume grants another budget");
        return false;
    }

    private boolean finish(ProcessingLedger.Run run, RunSnapshot snapshot) throws IOException {
        Path source = work.resolve("processing").resolve(run.id());
        Path archive = work.resolve("archive").resolve(run.id());
        Files.createDirectories(archive.getParent());
        if (Files.exists(source)) atomicMove(source, archive);
        if (!Files.exists(archive.resolve("input"))) {
            throw new IOException("Archived input missing; run remains recoverable");
        }
        String status = snapshot.mode() == IngestionProperties.Mode.PREVIEW ? "PREVIEWED" : "COMPLETED";
        ledger.status(run.id(), status, null);
        metrics.counter("fileadapter.files", "status", status).increment();
        log.info("Run {} {} ({} records)", run.id(), status, ledger.count(run.id()));
        return true;
    }

    private void fail(String id, String category, long record, String reason) throws IOException {
        report(id, category, record, reason);
        ledger.status(id, "FAILED", category + ": " + reason);
        Path source = work.resolve("processing").resolve(id);
        Path target = work.resolve("errors").resolve(id).resolve("input-files");
        if (Files.exists(source)) atomicMove(source, target);
        log.warn("Run {} failed: {} at record {}", id, category, record);
    }

    private void pause(String id, long record, String reason) throws IOException {
        report(id, "PAUSED", record, reason);
        ledger.status(id, "PAUSED", reason);
        log.warn("Run {} paused at record {}", id, record);
    }

    private void report(String id, String category, long record, String reason) throws IOException {
        Path directory = work.resolve("errors").resolve(id);
        Files.createDirectories(directory);
        var report = json.createObjectNode();
        report.put("runId", id); report.put("category", category);
        report.put("recordNumber", record); report.put("reason", reason);
        if (category.equals("PREFLIGHT")) {
            report.put("sourceRecordNumber", record);
        } else {
            report.put("requestNumber", record);
            var request = ledger.next(id, record - 1);
            if (request != null && request.number() == record) {
                report.put("sourceRecordNumber", request.sourceRecord());
            }
        }
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("error.json").toFile(), report);
    }

    public void rejectIntake(Path input, Exception error) throws IOException {
        Path target = work.resolve("errors/intake-" + java.util.UUID.randomUUID());
        Files.createDirectories(target);
        // Across filesystems, copy before delete, so a failed transfer keeps the original.
        Files.copy(input, target.resolve("input"));
        json.writeValue(target.resolve("error.json").toFile(),
                Map.of("category", "INTAKE", "fileName", input.getFileName().toString(),
                        "reason", error.getClass().getSimpleName()));
        Files.deleteIfExists(Path.of(input + properties.readySuffix()));
        Files.delete(input);
    }

    public boolean successful(String id) {
        var run = ledger.get(id);
        return run != null && (run.status().equals("COMPLETED") || run.status().equals("PREVIEWED"));
    }

    private static void validateDelivery(IngestionProperties.Delivery delivery) {
        if (delivery == null || delivery.endpoint() == null
                || !java.util.Set.of("http", "https").contains(delivery.endpoint().getScheme())
                || delivery.endpoint().getHost() == null || delivery.endpoint().getUserInfo() != null
                || delivery.endpoint().getFragment() != null) {
            throw new IllegalArgumentException("Delivery requires an HTTP(S) endpoint without embedded credentials");
        }
        if (delivery.maxAttempts() < 1 || delivery.maxAttempts() > 10
                || delivery.connectTimeout() == null || delivery.connectTimeout().isNegative()
                || delivery.connectTimeout().isZero() || delivery.readTimeout() == null
                || delivery.readTimeout().isNegative() || delivery.readTimeout().isZero()
                || delivery.retryDelay() == null || delivery.retryDelay().isNegative()
                || delivery.retryDelay().toMillis() > 30_000
                || delivery.successStatuses() == null || delivery.successStatuses().isEmpty()
                || delivery.successStatuses().stream().anyMatch(s -> s < 200 || s > 299)) {
            throw new IllegalArgumentException("Invalid delivery timeout, retry, or acknowledgement settings");
        }
        if (delivery.replaySafe() && (delivery.idempotencyHeader() == null
                || delivery.idempotencyHeader().isBlank())) {
            throw new IllegalArgumentException("Replay-safe delivery requires an agreed idempotency header");
        }
    }

    static void atomicMove(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String hash(Path file) throws IOException {
        var digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
