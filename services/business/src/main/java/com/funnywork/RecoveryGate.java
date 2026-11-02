package com.funnywork;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Component;

/** Instance admission and tenant transaction guard; physical executors still require separate fencing. */
@Component
public class RecoveryGate {
    private final JdbcTemplate db;
    private final boolean isolated;
    private final String expected;
    private final String lockClause;
    public RecoveryGate(JdbcTemplate db,@Value("${funnywork.recovery-isolation:false}") boolean isolated,
                        @Value("${funnywork.recovery-generation:initial}") String expected) {
        if(expected==null || !expected.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Invalid recovery generation");
        this.db=db;this.isolated=isolated;this.expected=expected;
        String product=db.execute((ConnectionCallback<String>) connection->connection.getMetaData().getDatabaseProductName());
        // PostgreSQL readers share this row; generation UPDATE waits for admitted transactions.
        // H2 test deployments use an exclusive row lock because FOR SHARE is unavailable.
        this.lockClause="PostgreSQL".equals(product)?" for share":" for update";
    }
    public boolean lockTransaction(boolean skipLocked) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Recovery lock requires a transaction");
        if(isolated) {
            if(skipLocked)return false;
            throw new ApiException(503,"RECOVERY_ISOLATION","Recovery isolation is active");
        }
        var rows=db.queryForList("select generation from recovery_generation where id=1"+lockClause+(skipLocked?" skip locked":""),String.class);
        if(rows.size()==1 && expected.equals(rows.get(0)))return true;
        if(skipLocked)return false;
        throw new ApiException(503,"RECOVERY_GENERATION_MISMATCH","Recovery generation does not match");
    }
    public String failure() {
        if(isolated)return "RECOVERY_ISOLATION";
        try {
            var rows=db.queryForList("select generation from recovery_generation where id=1",String.class);
            return rows.size()==1 && expected.equals(rows.get(0)) ? null : "RECOVERY_GENERATION_MISMATCH";
        } catch(DataAccessException error) {
            return "STORAGE_UNAVAILABLE";
        }
    }
}
