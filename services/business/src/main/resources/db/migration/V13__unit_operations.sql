ALTER TABLE external_operations ADD CONSTRAINT operations_tenant_identity UNIQUE(tenant_id,id);
CREATE TABLE unit_operations (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) PRIMARY KEY,
 operation_id VARCHAR(64) NOT NULL UNIQUE, input_json TEXT NOT NULL,
 FOREIGN KEY(tenant_id,unit_id) REFERENCES work_units(tenant_id,id),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES external_operations(tenant_id,id)
);
