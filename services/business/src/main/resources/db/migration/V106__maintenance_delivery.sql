CREATE TABLE maintenance_delivery_policies (
 repository_id VARCHAR(64) PRIMARY KEY REFERENCES maintenance_repositories(id), tenant_id VARCHAR(64) NOT NULL,
 platforms_json TEXT NOT NULL, checks_json TEXT NOT NULL, required_ci_json TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE maintenance_delivery_jobs (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, problem_id VARCHAR(64) NOT NULL REFERENCES maintenance_problems(id),
 task_id VARCHAR(64) NOT NULL UNIQUE REFERENCES tasks(id), problem_version INTEGER NOT NULL, candidate_sha VARCHAR(64) NOT NULL,
 mode VARCHAR(16) NOT NULL, platform VARCHAR(16) NOT NULL, state VARCHAR(32) NOT NULL, request_hash VARCHAR(64) NOT NULL, policy_hash VARCHAR(64) NOT NULL,
 created_at BIGINT NOT NULL, report_json TEXT, UNIQUE(problem_id,problem_version,mode,platform)
);
