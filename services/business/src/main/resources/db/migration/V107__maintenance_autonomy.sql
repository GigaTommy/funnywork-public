CREATE TABLE maintenance_automation_policies (
 repository_id VARCHAR(64) PRIMARY KEY REFERENCES maintenance_repositories(id),
 tenant_id VARCHAR(64) NOT NULL, operator_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 deployment_id VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 auto_merge BOOLEAN NOT NULL DEFAULT FALSE, environment_policy VARCHAR(40) NOT NULL,
 read_token_ref VARCHAR(120) NOT NULL, delivery_token_ref VARCHAR(120) NOT NULL,
 reviewer_token_ref VARCHAR(120) NOT NULL,
 revision INTEGER NOT NULL DEFAULT 1, updated_at BIGINT NOT NULL
);
CREATE TABLE maintenance_stage_runs (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 repository_id VARCHAR(64) NOT NULL REFERENCES maintenance_repositories(id),
 subject_id VARCHAR(160) NOT NULL, stage VARCHAR(32) NOT NULL,
 input_hash VARCHAR(64) NOT NULL, input_json TEXT NOT NULL, policy_revision INTEGER NOT NULL,
 state VARCHAR(24) NOT NULL, result_json TEXT, error_code VARCHAR(120),
 attempts INTEGER NOT NULL DEFAULT 0, next_at BIGINT NOT NULL DEFAULT 0,
 lease_until BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(repository_id,subject_id,stage,input_hash,policy_revision)
);
CREATE INDEX maintenance_stage_queue ON maintenance_stage_runs(state,next_at);
CREATE TABLE maintenance_risk_events (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, repository_id VARCHAR(64) NOT NULL,
 source_id VARCHAR(64) NOT NULL, reporter_id VARCHAR(128),
 rule_id VARCHAR(64) NOT NULL, evidence_json TEXT NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(source_id,rule_id)
);
CREATE TABLE maintenance_feedback_restrictions (
 repository_id VARCHAR(64) NOT NULL, source_kind VARCHAR(16) NOT NULL,
 reporter_id VARCHAR(128) NOT NULL, until_at BIGINT NOT NULL,
 PRIMARY KEY(repository_id,source_kind,reporter_id)
);
CREATE TABLE maintenance_external_actions (
 action_key VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 problem_id VARCHAR(64) NOT NULL, candidate_sha VARCHAR(64) NOT NULL,
 action_kind VARCHAR(24) NOT NULL, target VARCHAR(300) NOT NULL,
 state VARCHAR(24) NOT NULL, remote_id VARCHAR(120), result_json TEXT,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);

CREATE TABLE maintenance_acceptance_cases (
 problem_id VARCHAR(64) NOT NULL REFERENCES maintenance_problems(id),
 criterion_id VARCHAR(64) NOT NULL, stage VARCHAR(8) NOT NULL, platform VARCHAR(16) NOT NULL,
 check_id VARCHAR(64) NOT NULL, expected TEXT NOT NULL, policy_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(problem_id,criterion_id,platform)
);
