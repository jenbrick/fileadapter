package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.processing.ProcessingLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Save completed enriched rows so restarting preparation does not repeat lookups that already finished.
 */
@Component
public class EnrichedRecordStore {
    private final JdbcTemplate jdbc;
    // The ledger dependency makes Spring create its run table before we create our own tables.
    public EnrichedRecordStore(JdbcTemplate jdbc, ProcessingLedger ledger) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enriched_record (
              run_id VARCHAR(64) NOT NULL, source_record BIGINT NOT NULL, payload CLOB NOT NULL,
              PRIMARY KEY(run_id, source_record), FOREIGN KEY(run_id) REFERENCES ingestion_run(id))
            """);
    }
    // A completed saved row can skip both group and member lookup during preparation recovery.
    public String get(String run, long record) {
        var rows = jdbc.queryForList("SELECT payload FROM enriched_record WHERE run_id=? AND source_record=?",
                String.class, run, record);
        return rows.isEmpty() ? null : rows.get(0);
    }
    // Save only after the configured enrichment chain has completed for this row.
    public void save(String run, long record, String payload) {
        jdbc.update("INSERT INTO enriched_record(run_id,source_record,payload) VALUES(?,?,?)",run,record,payload);
    }
}
