CREATE TABLE maintenance_repositories (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, zone VARCHAR(64) NOT NULL,
 agent_id VARCHAR(64) NOT NULL REFERENCES agent_versions(id), slug VARCHAR(200) NOT NULL,
 base_branch VARCHAR(120) NOT NULL, checks_json TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(tenant_id,zone,slug)
);
CREATE TABLE maintenance_problems (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL,
 repository_id VARCHAR(64) NOT NULL REFERENCES maintenance_repositories(id), task_id VARCHAR(64) NOT NULL REFERENCES tasks(id),
 request_key VARCHAR(120) NOT NULL, request_hash VARCHAR(64) NOT NULL, title VARCHAR(160) NOT NULL,
 requirement_json TEXT NOT NULL, platform VARCHAR(16) NOT NULL, state VARCHAR(32) NOT NULL,
 version INTEGER NOT NULL DEFAULT 1, created_at BIGINT NOT NULL, acceptance_json TEXT,
 UNIQUE(tenant_id,owner_id,request_key), UNIQUE(task_id)
);
CREATE TABLE maintenance_specs (
 unit_id VARCHAR(64) PRIMARY KEY REFERENCES work_units(id), tenant_id VARCHAR(64) NOT NULL,
 problem_id VARCHAR(64) NOT NULL REFERENCES maintenance_problems(id), platform VARCHAR(16) NOT NULL,
 spec_json TEXT NOT NULL, spec_hash VARCHAR(64) NOT NULL
);
CREATE TABLE maintenance_node_profiles (
 node_id VARCHAR(64) PRIMARY KEY REFERENCES nodes(id), tenant_id VARCHAR(64) NOT NULL,
 platform VARCHAR(16) NOT NULL, codex_version VARCHAR(120) NOT NULL, git_version VARCHAR(120) NOT NULL,
 reported_at BIGINT NOT NULL
);
CREATE TABLE maintenance_reports (
 attempt_id VARCHAR(64) PRIMARY KEY REFERENCES attempts(id), unit_id VARCHAR(64) NOT NULL REFERENCES work_units(id),
 tenant_id VARCHAR(64) NOT NULL, report_hash VARCHAR(64) NOT NULL, report_json TEXT NOT NULL,
 artifact_id VARCHAR(64) NOT NULL REFERENCES artifacts(id), created_at BIGINT NOT NULL
);
CREATE INDEX maintenance_problem_page ON maintenance_problems(tenant_id,created_at,id);
