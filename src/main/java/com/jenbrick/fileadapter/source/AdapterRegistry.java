package com.jenbrick.fileadapter.source;

import com.jenbrick.fileadapter.config.Profile;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AdapterRegistry {
    private final Map<Profile.Format, FileFormatAdapter> adapters =
            new EnumMap<>(Profile.Format.class);

    public AdapterRegistry(List<FileFormatAdapter> implementations) {
        for (var adapter : implementations) {
            if (adapters.putIfAbsent(adapter.format(), adapter) != null) {
                throw new IllegalArgumentException("Duplicate adapter: " + adapter.format());
            }
        }
    }

    public FileFormatAdapter require(Profile.Format format) {
        var adapter = adapters.get(format);
        if (adapter == null) {
            throw new IllegalArgumentException("Adapter " + format
                    + " is not implemented. Register a FileFormatAdapter bean to enable it.");
        }
        return adapter;
    }
}
