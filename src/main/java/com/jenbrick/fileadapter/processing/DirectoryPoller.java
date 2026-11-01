package com.jenbrick.fileadapter.processing;

import com.jenbrick.fileadapter.config.IngestionProperties;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Look for finished incoming files and recover unfinished runs before taking in more work.
 */
@Component
public class DirectoryPoller {
    private static final Logger log = LoggerFactory.getLogger(DirectoryPoller.class);
    private final IngestionProperties properties;
    private final FileProcessor processor;

    public DirectoryPoller(IngestionProperties properties, FileProcessor processor) {
        this.properties = properties;
        this.processor = processor;
        if (properties.minimumAge() == null || properties.minimumAge().isNegative()
                || properties.readySuffix() == null || properties.readySuffix().isBlank()
                || properties.readySuffix().contains("/") || properties.readySuffix().contains("\\")) {
            throw new IllegalArgumentException("A nonnegative minimum age and ready-marker suffix are required");
        }
    }

    @Scheduled(fixedDelayString = "${ingestion.poll-interval:5s}", initialDelayString = "${ingestion.poll-interval:5s}")
    public void poll() {
        if (!properties.pollEnabled() || properties.file() != null || properties.resume() != null) return;
        try {
            // Finish recoverable work first so a restart does not simply start another copy of the
            // same file.
            processor.recover();
            Path incoming = properties.inputDirectory();
            Files.createDirectories(incoming);
            try (var files = Files.list(incoming)) {
                var iterator = files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !path.getFileName().toString().startsWith("."))
                        .filter(path -> !path.toString().endsWith(properties.readySuffix())).iterator();
                while (iterator.hasNext()) {
                    Path input = iterator.next();
                    Path marker = Path.of(input + properties.readySuffix());
                    // The producer publishes this marker after closing the file. Age alone does not
                    // prove a file is finished.
                    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (Files.getLastModifiedTime(input).toInstant()
                            .isAfter(Instant.now().minus(properties.minimumAge()))) continue;
                    try {
                        // Polling consumes the incoming copy only after the processor has saved it
                        // locally.
                        processor.process(input, null, true);
                    } catch (IllegalArgumentException e) {
                        if (Files.exists(input)) processor.rejectIntake(input, e);
                        log.warn("Input rejected before processing ({})", e.getClass().getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            // Do not log exception messages, which may include request bodies or credentials.
            log.error("Polling/recovery failed ({}); retained work will be retried", e.getClass().getSimpleName());
        }
    }
}
