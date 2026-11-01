package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.consent.GroupSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One frozen group resolution per external group ID per run, shared by its member rows. */
@Component
public class GroupContextStore {
    private final JdbcTemplate jdbc;
    public GroupContextStore(JdbcTemplate jdbc,com.jenbrick.fileadapter.processing.ProcessingLedger ledger) {
        this.jdbc=jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS group_context(run_id VARCHAR(64),external_group_id VARCHAR(1024),internal_group_number VARCHAR(1024) NOT NULL,customer_id VARCHAR(1024) NOT NULL,PRIMARY KEY(run_id,external_group_id),FOREIGN KEY(run_id) REFERENCES ingestion_run(id))");
    }
    public GroupSettings.Context get(String runId,String externalGroupId) {
        return jdbc.query("SELECT internal_group_number,customer_id FROM group_context WHERE run_id=? AND external_group_id=?",
                (rs,n)->new GroupSettings.Context(rs.getString(1),rs.getString(2)),runId,externalGroupId).stream().findFirst().orElse(null);
    }
    public void save(String runId,String externalGroupId,GroupSettings.Context context) {
        jdbc.update("INSERT INTO group_context(run_id,external_group_id,internal_group_number,customer_id) VALUES(?,?,?,?)",runId,externalGroupId,context.internalGroupNumber(),context.customerId());
    }
}
