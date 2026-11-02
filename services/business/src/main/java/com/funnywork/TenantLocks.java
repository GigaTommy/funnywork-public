package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All scheduling mutations within a tenant use this lock, including authorization revocation. */
@Component
public class TenantLocks {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RecoveryGate recovery;
    public TenantLocks(JdbcTemplate db, PlatformTransactionManager transactions,RecoveryGate recovery) {
        this.db=db;this.tx=new TransactionTemplate(transactions);this.recovery=recovery;
    }
    public void ensure(String tenant) {
        if(db.queryForObject("select count(*) from tenant_guards where tenant_id=?",Integer.class,tenant)>0)return;
        // The bootstrap lock is only for first-time tenant creation, never for normal task scheduling.
        tx.executeWithoutResult(status->{
            db.queryForObject("select id from scheduler_guard where id=1 for update",Integer.class);
            if(db.queryForObject("select count(*) from tenant_guards where tenant_id=?",Integer.class,tenant)==0)
                db.update("insert into tenant_guards(tenant_id) values(?)",tenant);
        });
    }
    public void lock(String tenant) {
        recovery.lockTransaction(false);
        if(db.queryForList("select tenant_id from tenant_guards where tenant_id=? for update",tenant).isEmpty())
            throw ApiException.forbidden();
    }
    public boolean tryLock(String tenant) {
        if(!recovery.lockTransaction(true))return false;
        return !db.queryForList("select tenant_id from tenant_guards where tenant_id=? for update skip locked",tenant).isEmpty();
    }
}
