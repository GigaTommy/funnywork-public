-- One evidence-verified recovery execution per problem, separate from its original retry window.
CREATE TABLE maintenance_runtime_repairs (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 problem_id VARCHAR(64) NOT NULL UNIQUE REFERENCES maintenance_problems(id),
 expected_version INTEGER NOT NULL, failed_task_id VARCHAR(64) NOT NULL,
 evidence_digest VARCHAR(64) NOT NULL, state VARCHAR(24) NOT NULL,
 task_id VARCHAR(64), node_id VARCHAR(64), device_id VARCHAR(64), client_version VARCHAR(80), policy_device_id VARCHAR(64),
 created_at BIGINT NOT NULL, consumed_at BIGINT
);
CREATE INDEX maintenance_repair_task ON maintenance_runtime_repairs(task_id);
