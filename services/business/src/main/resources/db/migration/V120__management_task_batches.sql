CREATE TABLE management_task_batches (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, actor_id VARCHAR(64) NOT NULL,
 request_key VARCHAR(120) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 action VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(tenant_id,actor_id,request_key)
);
CREATE TABLE management_task_batch_items (
 batch_id VARCHAR(64) NOT NULL REFERENCES management_task_batches(id), position INTEGER NOT NULL,
 -- Target visibility/existence is checked per command, without a cross-tenant FK existence oracle.
 task_id VARCHAR(64) NOT NULL, expected_token VARCHAR(64) NOT NULL,
 state VARCHAR(20) NOT NULL, result_json TEXT NOT NULL DEFAULT '{}',
 PRIMARY KEY(batch_id,position), UNIQUE(batch_id,task_id)
);
