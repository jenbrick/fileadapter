package com.jenbrick.fileadapter.consent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Freeze each successfully built member plan before staging. Recovery reuses its lookups and timestamps. */
@Component
public class ConsentPlanStore {
    private final JdbcTemplate jdbc;
    public ConsentPlanStore(JdbcTemplate jdbc, com.jenbrick.fileadapter.processing.ProcessingLedger ledger) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS consent_plan (
              run_id VARCHAR(64) NOT NULL, source_record BIGINT NOT NULL, plan CLOB NOT NULL,
              PRIMARY KEY(run_id, source_record),
              FOREIGN KEY(run_id) REFERENCES ingestion_run(id))
            """);
    }
    public String get(String run, long record) {
        var rows = jdbc.queryForList("SELECT plan FROM consent_plan WHERE run_id=? AND source_record=?",
                String.class, run, record);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public void save(String run, long record, String plan) {
        jdbc.update("INSERT INTO consent_plan(run_id,source_record,plan) VALUES(?,?,?)", run, record, plan);
    }
}
