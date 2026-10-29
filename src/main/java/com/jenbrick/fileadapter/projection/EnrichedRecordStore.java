package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.processing.ProcessingLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Freeze enrichment before mapping, so interrupted preflight can reuse external lookup results. */
@Component
public class EnrichedRecordStore {
    private final JdbcTemplate jdbc;
    public EnrichedRecordStore(JdbcTemplate jdbc, ProcessingLedger ledger) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS enriched_record (
              run_id VARCHAR(64) NOT NULL, source_record BIGINT NOT NULL, payload CLOB NOT NULL,
              PRIMARY KEY(run_id, source_record), FOREIGN KEY(run_id) REFERENCES ingestion_run(id))
            """);
    }
    public String get(String run, long record) {
        var rows = jdbc.queryForList("SELECT payload FROM enriched_record WHERE run_id=? AND source_record=?",
                String.class, run, record);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public void save(String run, long record, String payload) {
        jdbc.update("INSERT INTO enriched_record(run_id,source_record,payload) VALUES(?,?,?)",run,record,payload);
    }
}
