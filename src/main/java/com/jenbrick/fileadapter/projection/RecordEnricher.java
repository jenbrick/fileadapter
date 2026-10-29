package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.source.SourceRecord;

/** Business-specific read-only enrichment, independent of output field names or JSON shape. */
public interface RecordEnricher {
    String name();
    default void validate(Profile profile) {}
    SourceRecord enrich(SourceRecord source, Profile profile, Mode mode);
}
