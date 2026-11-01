package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.consent.GroupSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Save one group result per run and external group ID. All of that group's member rows reuse the
 * same context.
 */
@Component
public class GroupContextStore {
    private final JdbcTemplate jdbc;
    // The ledger dependency makes Spring create its run table before we create our own tables.
    public GroupContextStore(JdbcTemplate jdbc,com.jenbrick.fileadapter.processing.ProcessingLedger ledger) {
        this.jdbc=jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS group_context(run_id VARCHAR(64),external_group_id VARCHAR(1024),internal_group_number VARCHAR(1024) NOT NULL,customer_id VARCHAR(1024) NOT NULL,PRIMARY KEY(run_id,external_group_id),FOREIGN KEY(run_id) REFERENCES ingestion_run(id))");
    }
    // Use run ID as part of the key so a later file gets a fresh group resolution.
    public GroupSettings.Context get(String runId,String externalGroupId) {
        return jdbc.query("SELECT internal_group_number,customer_id FROM group_context WHERE run_id=? AND external_group_id=?",
                (rs,n)->new GroupSettings.Context(rs.getString(1),rs.getString(2)),runId,externalGroupId).stream().findFirst().orElse(null);
    }
    // Keep the first successful group result stable for all member rows in this run.
    public void save(String runId,String externalGroupId,GroupSettings.Context context) {
        jdbc.update("INSERT INTO group_context(run_id,external_group_id,internal_group_number,customer_id) VALUES(?,?,?,?)",runId,externalGroupId,context.internalGroupNumber(),context.customerId());
    }
}
