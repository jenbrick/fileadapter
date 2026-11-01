package com.jenbrick.fileadapter.source;

import com.jenbrick.fileadapter.config.Profile;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Find the adapter for the configured file format. A new delimiter uses the delimited adapter; new
 * syntax needs a new adapter.
 */
@Component
public class AdapterRegistry {
    private final Map<Profile.Format, FileFormatAdapter> adapters =
            new EnumMap<>(Profile.Format.class);

    public AdapterRegistry(List<FileFormatAdapter> implementations) {
        // Spring supplies the registered adapters. Two adapters cannot own the same configured format.
        for (var adapter : implementations) {
            if (adapters.putIfAbsent(adapter.format(), adapter) != null) {
                throw new IllegalArgumentException("Duplicate adapter: " + adapter.format());
            }
        }
    }

    // The processor asks for the selected format; it does not choose a parser by file extension itself.
    public FileFormatAdapter require(Profile.Format format) {
        var adapter = adapters.get(format);
        if (adapter == null) {
            throw new IllegalArgumentException("Adapter " + format
                    + " is not implemented. Register a FileFormatAdapter bean to enable it.");
        }
        return adapter;
    }
}
