-- Bounded read-only checks, independent of repository mutation and delivery tasks.
CREATE TABLE maintenance_sync_checks (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL,
 owner_auth_version BIGINT NOT NULL, repository_id VARCHAR(64) NOT NULL, request_key VARCHAR(120) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 base_sha VARCHAR(64) NOT NULL, expected_version VARCHAR(80) NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(tenant_id,owner_id,request_key)
);
CREATE TABLE maintenance_sync_check_targets (
 id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL REFERENCES maintenance_sync_checks(id),
 tenant_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL, runtime_epoch VARCHAR(120) NOT NULL,
 platform VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL, spec_json TEXT NOT NULL, spec_hash VARCHAR(64) NOT NULL,
 expires_at BIGINT NOT NULL, receipt_json TEXT, receipt_hash VARCHAR(64),
 UNIQUE(task_id,device_id)
);
CREATE INDEX maintenance_sync_check_device ON maintenance_sync_check_targets(tenant_id,device_id,state);
