package com.jenbrick.fileadapter.source;

import com.jenbrick.fileadapter.config.Profile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Every file reader implements this interface. It reads source records; it does not perform lookups
 * or build consent requests.
 */
public interface FileFormatAdapter {
    Profile.Format format();
    void read(Path file, Profile.Layout layout, Consumer<SourceRecord> consumer)
            throws IOException;
}
