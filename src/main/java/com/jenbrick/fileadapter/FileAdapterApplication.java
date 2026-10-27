package com.jenbrick.fileadapter;

import com.jenbrick.fileadapter.config.IngestionProperties;
import com.jenbrick.fileadapter.processing.FileProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FileAdapterApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(FileAdapterApplication.class, args);
        var properties = context.getBean(IngestionProperties.class);
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
                String runId = processor.process(properties.file(), properties.profileId(), false);
                success = processor.successful(runId);
            }
            code = success ? 0 : 1;
        } catch (Exception e) {
            System.err.println("Processing failed (" + e.getClass().getSimpleName()
                    + "); inspect the processing ledger and error report.");
        } finally {
            context.close();
        }
        System.exit(code);
    }
}
