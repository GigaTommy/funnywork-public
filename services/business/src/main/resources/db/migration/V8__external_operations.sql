CREATE TABLE external_operations (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL,
 task_id VARCHAR(64) NOT NULL, tool_name VARCHAR(120) NOT NULL, business_key_hash VARCHAR(64) NOT NULL,
 input_hash VARCHAR(64) NOT NULL, contract_hash VARCHAR(64) NOT NULL, effect VARCHAR(32) NOT NULL,
 state VARCHAR(24) NOT NULL CHECK (state IN ('PREPARED','DISPATCHED','CONFIRMED','REJECTED','UNKNOWN','COMPENSATED')),
 receipt_ref VARCHAR(200), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 UNIQUE(tenant_id,tool_name,business_key_hash),
 FOREIGN KEY(tenant_id,owner_id) REFERENCES principals(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES tasks(tenant_id,id)
);
CREATE INDEX operations_reconcile_idx ON external_operations(tenant_id,state,updated_at);
