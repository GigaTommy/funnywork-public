CREATE TABLE operation_reconcile_grants (
 tenant_id VARCHAR(64) NOT NULL, operation_id VARCHAR(64) NOT NULL, principal_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(operation_id,principal_id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES external_operations(tenant_id,id),
 FOREIGN KEY(tenant_id,principal_id) REFERENCES principals(tenant_id,id)
);
CREATE TABLE unit_reconciliations (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) PRIMARY KEY, operation_id VARCHAR(64) NOT NULL UNIQUE,
 reviewer_id VARCHAR(64) NOT NULL, result_json TEXT NOT NULL, created_at BIGINT NOT NULL,
 FOREIGN KEY(tenant_id,unit_id) REFERENCES work_units(tenant_id,id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES external_operations(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewer_id) REFERENCES principals(tenant_id,id)
);
