-- Additive indexes for bounded organization audit queries; existing records are unchanged.
CREATE INDEX IF NOT EXISTS fw_directory_jobs_audit_idx ON fw_directory_jobs(tenant_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS fw_directory_runs_audit_idx ON fw_directory_runs(tenant_id,created_at DESC,id DESC);
