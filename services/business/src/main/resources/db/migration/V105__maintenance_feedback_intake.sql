CREATE TABLE maintenance_intake_configs (
 repository_id VARCHAR(64) PRIMARY KEY REFERENCES maintenance_repositories(id), tenant_id VARCHAR(64) NOT NULL,
 operator_id VARCHAR(64) NOT NULL REFERENCES principals(id), base_sha VARCHAR(64) NOT NULL,
 allowed_paths TEXT NOT NULL, default_platform VARCHAR(16) NOT NULL, auto_dispatch BOOLEAN NOT NULL DEFAULT FALSE,
 github_enabled BOOLEAN NOT NULL DEFAULT FALSE, github_token_ref VARCHAR(120) NOT NULL DEFAULT '',
 github_since VARCHAR(40) NOT NULL, github_error VARCHAR(120), updated_at BIGINT NOT NULL
);
CREATE TABLE maintenance_intakes (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, repository_id VARCHAR(64) NOT NULL REFERENCES maintenance_repositories(id),
 fingerprint VARCHAR(64) NOT NULL, title VARCHAR(160) NOT NULL, body TEXT NOT NULL, platform VARCHAR(16) NOT NULL,
 state VARCHAR(32) NOT NULL, revision INTEGER NOT NULL DEFAULT 1, analysis_json TEXT,
 problem_id VARCHAR(64) REFERENCES maintenance_problems(id), duplicate_of VARCHAR(64) REFERENCES maintenance_intakes(id),
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(tenant_id,repository_id,fingerprint)
);
CREATE TABLE maintenance_intake_sources (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, repository_id VARCHAR(64) NOT NULL,
 intake_id VARCHAR(64) NOT NULL REFERENCES maintenance_intakes(id), source_kind VARCHAR(16) NOT NULL,
 source_key VARCHAR(200) NOT NULL, reporter_id VARCHAR(64), source_url VARCHAR(300), source_revision VARCHAR(64) NOT NULL, source_updated VARCHAR(40),
 payload_json TEXT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(tenant_id,repository_id,source_kind,source_key)
);
CREATE TABLE maintenance_intake_attempts (
 id VARCHAR(64) PRIMARY KEY, intake_id VARCHAR(64) NOT NULL REFERENCES maintenance_intakes(id), tenant_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL REFERENCES sync_devices(id), node_id VARCHAR(64) NOT NULL REFERENCES nodes(id),
 runtime_epoch VARCHAR(64) NOT NULL, revision INTEGER NOT NULL, state VARCHAR(16) NOT NULL,
 expires_at BIGINT NOT NULL, spec_json TEXT NOT NULL, spec_hash VARCHAR(64) NOT NULL, report_hash VARCHAR(64)
);
CREATE INDEX maintenance_intake_queue ON maintenance_intakes(tenant_id,state,created_at);
CREATE INDEX maintenance_intake_active ON maintenance_intake_attempts(node_id,state);
