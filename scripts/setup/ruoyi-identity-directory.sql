-- FunnyWorker private deployment identity directory. Additive, repeatable PostgreSQL migration.
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS external_source VARCHAR(64);
-- Reject concurrent local/imported name collisions. Existing duplicates must be resolved before upgrade.
CREATE UNIQUE INDEX IF NOT EXISTS fw_sys_user_active_name_idx ON sys_user(tenant_id,user_name) WHERE del_flag='0';
CREATE TABLE IF NOT EXISTS fw_directory_sources (
 tenant_id VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL,
 checkpoint TEXT NOT NULL DEFAULT '', generation BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,source_id)
);
CREATE TABLE IF NOT EXISTS fw_directory_users (
 tenant_id VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL, external_id VARCHAR(128) NOT NULL,
 user_id BIGINT NOT NULL UNIQUE REFERENCES sys_user(user_id), remote_version BIGINT NOT NULL,
 content_hash VARCHAR(64) NOT NULL, remote_enabled BOOLEAN NOT NULL,
 PRIMARY KEY(tenant_id,source_id,external_id)
);
CREATE TABLE IF NOT EXISTS fw_directory_runs (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL,
 actor_id BIGINT NOT NULL, mode VARCHAR(16) NOT NULL, state VARCHAR(16) NOT NULL,
 generation BIGINT NOT NULL, created_at BIGINT NOT NULL, completed_at BIGINT,
 added INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, skipped INTEGER NOT NULL DEFAULT 0,
 error_code VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS fw_directory_runs_tenant_idx ON fw_directory_runs(tenant_id,created_at);
