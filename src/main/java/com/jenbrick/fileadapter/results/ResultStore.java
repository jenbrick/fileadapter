package com.jenbrick.fileadapter.results;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jenbrick.fileadapter.source.SourceRecord;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keep the original source rows and every diagnostic finding so results can be rebuilt without
 * rereading changed input.
 */
@Component
public class ResultStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public record Source(long number,String payload,String rawText,String contractKey,String contractId,String memberGuid) {}
    public record Finding(String severity,String scope,String code,String customerRemarks,String internalRemarks,
                          List<Long> sourceRecords,Long requestNumber) {}
    // The ledger dependency makes Spring create its run table before we create our own tables.
    public ResultStore(JdbcTemplate jdbc,ObjectMapper json,com.jenbrick.fileadapter.processing.ProcessingLedger ledger) {
        this.jdbc=jdbc;this.json=json;
        jdbc.execute("CREATE TABLE IF NOT EXISTS result_source(run_id VARCHAR(64),row_number BIGINT,payload CLOB,raw_text CLOB,contract_key VARCHAR(1024),contract_id VARCHAR(1024),member_guid VARCHAR(1024),PRIMARY KEY(run_id,row_number))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS result_finding(run_id VARCHAR(64),event_key VARCHAR(64),payload CLOB,PRIMARY KEY(run_id,event_key))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS result_intake(run_id VARCHAR(64) PRIMARY KEY,complete BOOLEAN)");
    }
    // STAGING has sent no requests, so restart its source capture and findings together.
    public void reset(String id) {
        jdbc.update("DELETE FROM result_source WHERE run_id=?",id);
        jdbc.update("DELETE FROM result_finding WHERE run_id=?",id);
        jdbc.update("MERGE INTO result_intake(run_id,complete) KEY(run_id) VALUES(?,false)",id);
    }
    // Save original values before normalization or enrichment changes the working record.
    public void capture(String id,SourceRecord row) {
        try {jdbc.update("INSERT INTO result_source(run_id,row_number,payload,raw_text) VALUES(?,?,?,?)",id,row.number(),json.writeValueAsString(row.payload()),row.rawText());}
        catch(java.io.IOException e){throw new IllegalStateException("Cannot save original source",e);}
    }
    // Add lookup identities separately so service results can be matched without altering the
    // original record.
    public void identify(String id,long row,String key,String contract,String member) {
        jdbc.update("UPDATE result_source SET contract_key=COALESCE(?,contract_key),contract_id=COALESCE(?,contract_id),member_guid=COALESCE(?,member_guid) WHERE run_id=? AND row_number=?",key,contract,member,id,row);
    }
    public List<Source> sources(String id) {
        return jdbc.query("SELECT * FROM result_source WHERE run_id=? ORDER BY row_number",(r,n)->new Source(r.getLong("row_number"),r.getString("payload"),r.getString("raw_text"),r.getString("contract_key"),r.getString("contract_id"),r.getString("member_guid")),id);
    }
    public void complete(String id) { jdbc.update("UPDATE result_intake SET complete=true WHERE run_id=?",id); }
    public boolean completeInput(String id) {return Boolean.TRUE.equals(jdbc.queryForList("SELECT complete FROM result_intake WHERE run_id=?",Boolean.class,id).stream().findFirst().orElse(false));}
    // Hash the finding itself so reapplying a saved response does not duplicate the IT or customer
    // result.
    public void add(String id,Finding finding) {
        try {
            String payload=json.writeValueAsString(finding);
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            jdbc.update("MERGE INTO result_finding(run_id,event_key,payload) KEY(run_id,event_key) VALUES(?,?,?)",id,key,payload);
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException("Cannot save finding",e);}
    }
    public List<Finding> findings(String id) {
        return jdbc.query("SELECT payload FROM result_finding WHERE run_id=? ORDER BY event_key",(r,n)->{
            try {return json.readValue(r.getString(1),Finding.class);}catch(java.io.IOException e){throw new java.sql.SQLException("Invalid finding",e);}
        },id);
    }
    // An operational hold with no failed row references does not by itself mean customer records
    // were rejected.
    public boolean rejected(String id) {return findings(id).stream().anyMatch(f->!f.severity().equals("SOFT") && !f.sourceRecords().isEmpty());}
}
