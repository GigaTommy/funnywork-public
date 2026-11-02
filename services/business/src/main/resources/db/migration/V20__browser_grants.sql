CREATE TABLE browser_grants (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) PRIMARY KEY,
 policy_json TEXT NOT NULL, enabled BOOLEAN NOT NULL,
 FOREIGN KEY (tenant_id,unit_id) REFERENCES work_units(tenant_id,id)
);
