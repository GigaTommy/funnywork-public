CREATE TABLE reconciliation_runs (
 tenant_id VARCHAR(64) NOT NULL, operation_id VARCHAR(64) PRIMARY KEY,
 generation BIGINT NOT NULL, expires_at BIGINT NOT NULL, next_at BIGINT NOT NULL,
 failures INTEGER NOT NULL,
 FOREIGN KEY(tenant_id,operation_id) REFERENCES external_operations(tenant_id,id)
);
CREATE INDEX reconciliation_due_idx ON reconciliation_runs(tenant_id,next_at,expires_at);
