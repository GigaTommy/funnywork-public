-- A wall-clock expiry does not establish process quiescence; claimed checks remain quarantined.
ALTER TABLE maintenance_sync_check_targets ADD COLUMN lease_expires_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE maintenance_sync_check_targets ADD COLUMN stopped_evidence_digest VARCHAR(64);
