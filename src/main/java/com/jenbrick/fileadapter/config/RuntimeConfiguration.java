package com.jenbrick.fileadapter.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import javax.sql.DataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfiguration {
    @Bean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder().findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    @Bean(destroyMethod = "close")
    public WorkDirectoryLock workDirectoryLock(IngestionProperties properties) throws IOException {
        return new WorkDirectoryLock(properties);
    }

    @Bean
    public DataSource dataSource(IngestionProperties properties, WorkDirectoryLock lock) {
        String path = properties.workDirectory().toAbsolutePath().normalize().resolve("state/ledger").toString();
        return DataSourceBuilder.create().driverClassName("org.h2.Driver")
                .url("jdbc:h2:file:" + path + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0")
                .username("sa").password("").build();
    }

    /** Supported deployment: one process, local durable work directory, LAN input mount. */
    public static final class WorkDirectoryLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        WorkDirectoryLock(IngestionProperties properties) throws IOException {
            var root = properties.workDirectory().toAbsolutePath().normalize();
            Files.createDirectories(root.resolve("state"));
            channel = FileChannel.open(root.resolve("state/process.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try {
                acquired = channel.tryLock();
                if (acquired == null) throw new IllegalStateException("Work directory is already in use");
            } catch (Exception e) {
                channel.close();
                throw e;
            }
            lock = acquired;
        }

        @Override public void close() throws IOException {
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
