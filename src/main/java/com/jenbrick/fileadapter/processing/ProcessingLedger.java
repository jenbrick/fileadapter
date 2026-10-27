package com.jenbrick.fileadapter.processing;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProcessingLedger {
    private final JdbcTemplate jdbc;

    public record Run(String id, String filename, String contentHash, String snapshot,
                      String status, String error) {}
    public record Request(long number, String payload, String status, int attempts) {}

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

    public List<String> recoverable() {
        return jdbc.queryForList("""
                SELECT id FROM ingestion_run WHERE status IN
                ('STAGING','READY','DELIVERING','FINALIZING') ORDER BY updated_at
                """, String.class);
    }

    public void status(String id, String status, String error) {
        jdbc.update("UPDATE ingestion_run SET status=?,error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, error, id);
    }

    public void clearRequests(String id) {
        jdbc.update("DELETE FROM ingestion_request WHERE run_id=?", id);
    }

    public void stage(String id, long record, String payload) {
        jdbc.update("INSERT INTO ingestion_request(run_id,record_number,payload,status) VALUES(?,?,?,?)",
                id, record, payload, "PENDING");
    }

    public Request next(String id, long after) {
        var rows = jdbc.query("""
                SELECT record_number,payload,status,attempts FROM ingestion_request
                WHERE run_id=? AND record_number>? ORDER BY record_number LIMIT 1
                """, (rs, n) -> new Request(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)), id, after);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long count(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_request WHERE run_id=?", Long.class, id);
    }

    public void sending(String id, long record) {
        jdbc.update("""
                UPDATE ingestion_request SET status='SENDING',attempts=attempts+1
                WHERE run_id=? AND record_number=?
                """, id, record);
    }

    public void result(String id, long record, String status, Integer httpStatus) {
        jdbc.update("UPDATE ingestion_request SET status=?,http_status=? WHERE run_id=? AND record_number=?",
                status, httpStatus, id, record);
    }
}
