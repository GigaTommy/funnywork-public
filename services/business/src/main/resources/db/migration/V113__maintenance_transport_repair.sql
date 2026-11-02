-- Preserve the first repair and all reports; allow one distinct, evidence-bound transport repair.
-- A replacement table keeps this migration portable across PostgreSQL and the H2 test database.
CREATE TABLE maintenance_runtime_repairs_v2 (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
 problem_id VARCHAR(64) NOT NULL REFERENCES maintenance_problems(id),
 expected_version INTEGER NOT NULL CHECK (expected_version IN (4,5)),
 repair_kind VARCHAR(32) NOT NULL DEFAULT 'LEASE_RUNTIME' CHECK (repair_kind IN ('LEASE_RUNTIME','MODEL_TRANSPORT')),
 failed_task_id VARCHAR(64) NOT NULL, evidence_digest VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL,
 task_id VARCHAR(64), node_id VARCHAR(64), device_id VARCHAR(64), client_version VARCHAR(80), policy_device_id VARCHAR(64),
 created_at BIGINT NOT NULL, consumed_at BIGINT,
 UNIQUE(problem_id,repair_kind), UNIQUE(problem_id,expected_version), UNIQUE(problem_id,evidence_digest)
);
INSERT INTO maintenance_runtime_repairs_v2
 (id,tenant_id,problem_id,expected_version,repair_kind,failed_task_id,evidence_digest,state,task_id,node_id,device_id,client_version,policy_device_id,created_at,consumed_at)
 SELECT id,tenant_id,problem_id,expected_version,'LEASE_RUNTIME',failed_task_id,evidence_digest,state,task_id,node_id,device_id,client_version,policy_device_id,created_at,consumed_at
 FROM maintenance_runtime_repairs;
DROP TABLE maintenance_runtime_repairs;
ALTER TABLE maintenance_runtime_repairs_v2 RENAME TO maintenance_runtime_repairs;
CREATE INDEX maintenance_repair_task ON maintenance_runtime_repairs(task_id);
