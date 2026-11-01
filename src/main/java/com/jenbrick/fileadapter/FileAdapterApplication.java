package com.jenbrick.fileadapter;

import com.jenbrick.fileadapter.config.IngestionProperties;
import com.jenbrick.fileadapter.processing.FileProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Start Spring, then either process one file, resume a saved run, or leave the directory poller
 * running.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FileAdapterApplication {
    public static void main(String[] args) {
        // Load the profiles and build the Spring components before choosing CLI or polling mode.
        var context = SpringApplication.run(FileAdapterApplication.class, args);
        var properties = context.getBean(IngestionProperties.class);
        // With no file or resume argument, leave the application running for the poller.
        if (properties.file() == null && properties.resume() == null) return;
        int code = 1;
        try {
            if (properties.file() != null && properties.resume() != null) {
                throw new IllegalArgumentException("Choose either file or resume");
            }
            var processor = context.getBean(FileProcessor.class);
            boolean success;
            if (properties.resume() != null) {
                success = processor.resume(properties.resume());
            } else {
                // A CLI run copies the input. The original file stays where it was.
                String runId = processor.process(properties.file(), properties.profileId(), false);
                success = processor.successful(runId);
            }
            code = success ? 0 : 1;
        } catch (Exception e) {
            System.err.println("Processing failed (" + e.getClass().getSimpleName()
                    + "); inspect the processing ledger and error report.");
        } finally {
            // Close the database and release the work-directory lock before exiting.
            context.close();
        }
        System.exit(code);
    }
}
