CREATE TABLE browser_actions (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL,
 action_id VARCHAR(100) NOT NULL, attempt_id VARCHAR(64) NOT NULL,
 action_type VARCHAR(16) NOT NULL, signature VARCHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(unit_id,action_id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES work_units(tenant_id,id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES attempts(tenant_id,id)
);
