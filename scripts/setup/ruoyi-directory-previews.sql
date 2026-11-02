-- Additive, repeatable migration. Preview payload contains user profiles, never passwords.
ALTER TABLE fw_directory_jobs ADD COLUMN IF NOT EXISTS preview_only BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE IF NOT EXISTS fw_directory_previews (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL,
 generation BIGINT NOT NULL, checkpoint TEXT NOT NULL, payload TEXT NOT NULL,
 plan TEXT NOT NULL, source_hash VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL,
 created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, approved_by BIGINT, applied_at BIGINT,
 purged BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS fw_directory_previews_expiry_idx ON fw_directory_previews(expires_at);
