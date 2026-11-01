package com.jenbrick.fileadapter.processing;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Save run and request status in H2 so we know what is prepared, what was sent, and what still
 * needs attention.
 */
@Component
public class ProcessingLedger {
    private final JdbcTemplate jdbc;

    public record Run(String id, String filename, String contentHash, String snapshot,
                      String status, String error) {}
    // The request number is separate from the source rows that contributed to this body.
    public record Request(long number, String payload, String status, int attempts, long sourceRecord,
                          String sourceRecords, String responseIssues) {}

    public ProcessingLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ingestion_run (
                  id VARCHAR(64) PRIMARY KEY, filename VARCHAR(1024) NOT NULL,
                  content_hash VARCHAR(64) NOT NULL, snapshot CLOB NOT NULL,
                  status VARCHAR(32) NOT NULL, error VARCHAR(2048),
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ingestion_request (
                  run_id VARCHAR(64) NOT NULL, record_number BIGINT NOT NULL,
                  payload CLOB NOT NULL, status VARCHAR(32) NOT NULL,
                  attempts INT DEFAULT 0 NOT NULL, http_status INT,
                  PRIMARY KEY(run_id, record_number),
                  FOREIGN KEY(run_id) REFERENCES ingestion_run(id))
                """);
        // Keep the schema upgrades so existing work directories can still be opened.
        jdbc.execute("ALTER TABLE ingestion_request ADD COLUMN IF NOT EXISTS source_record BIGINT");
        jdbc.execute("ALTER TABLE ingestion_request ADD COLUMN IF NOT EXISTS source_records CLOB");
        jdbc.execute("ALTER TABLE ingestion_request ADD COLUMN IF NOT EXISTS response_issues CLOB");
    }

    public void create(String id, String filename, String contentHash, String snapshot) {
        jdbc.update("INSERT INTO ingestion_run(id,filename,content_hash,snapshot,status) VALUES(?,?,?,?,?)",
                id, filename, contentHash, snapshot, "STAGING");
    }

    public Run get(String id) {
        var rows = jdbc.query("SELECT * FROM ingestion_run WHERE id=?", (rs, n) ->
                new Run(rs.getString("id"), rs.getString("filename"), rs.getString("content_hash"),
                        rs.getString("snapshot"), rs.getString("status"), rs.getString("error")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // These phases can continue automatically. PAUSED and FAILED runs need explicit attention.
    public List<String> recoverable() {
        return jdbc.queryForList("""
                SELECT id FROM ingestion_run WHERE status IN
                ('STAGING','READY','DELIVERING','FINALIZING') ORDER BY updated_at
                """, String.class);
    }

    public List<String> previewedRuns() {
        return jdbc.queryForList("SELECT id FROM ingestion_run WHERE status IN ('PREVIEWED','PREVIEWED_WITH_ERRORS') ORDER BY updated_at",
                String.class);
    }

    public List<String> reportableRuns() {
        return jdbc.queryForList("SELECT id FROM ingestion_run WHERE status IN ('PREVIEWED','PREVIEWED_WITH_ERRORS','COMPLETED','COMPLETED_WITH_ERRORS','FAILED','PAUSED')",String.class);
    }

    public void status(String id, String status, String error) {
        jdbc.update("UPDATE ingestion_run SET status=?,error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, error, id);
    }

    public void clearRequests(String id) {
        jdbc.update("DELETE FROM ingestion_request WHERE run_id=?", id);
    }

    // Store the whole body together with every source record that contributed to it.
    public void stage(String id,long record,List<Long> sourceRecords,String payload) {
        if(sourceRecords.isEmpty()) throw new IllegalArgumentException("Request needs source provenance");
        jdbc.update("INSERT INTO ingestion_request(run_id,record_number,payload,status,source_record,source_records) VALUES(?,?,?,?,?,?)",
                id,record,payload,"PENDING",sourceRecords.get(0),sourceRecords.toString());
    }

    // Read by request number so delivery and recovery follow the original order.
    public Request next(String id, long after) {
        var rows = jdbc.query("""
                SELECT record_number,payload,status,attempts,COALESCE(source_record,record_number),source_records,response_issues FROM ingestion_request
                WHERE run_id=? AND record_number>? ORDER BY record_number LIMIT 1
                """, (rs, n) -> new Request(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5), rs.getString(6),rs.getString(7)), id, after);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_request WHERE run_id=?", Long.class, id);
    }

    // Record the attempt before POSTing. A crash after this point must be treated as a possible send.
    public void sending(String id, long record) {
        jdbc.update("""
                UPDATE ingestion_request SET status='SENDING',attempts=attempts+1
                WHERE run_id=? AND record_number=?
                """, id, record);
    }

    // Keep acknowledgement and structured issues in the same database update.
    public void result(String id,long record,String status,Integer httpStatus,String issues) {
        jdbc.update("UPDATE ingestion_request SET status=?,http_status=?,response_issues=? WHERE run_id=? AND record_number=?",
                status,httpStatus,issues,id,record);
    }

}
