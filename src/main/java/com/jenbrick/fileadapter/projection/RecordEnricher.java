package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.source.SourceRecord;

/**
 * Add values to a source record without deciding the final JSON shape. Lookups here must be
 * read-only because they may repeat.
 */
public interface RecordEnricher {
    String name();
    default SourceRecord enrichForRun(String runId,SourceRecord source,Profile profile,Mode mode) {
        return enrich(source,profile,mode);
    }
    default void validate(Profile profile) {}
    SourceRecord enrich(SourceRecord source, Profile profile, Mode mode);
}
