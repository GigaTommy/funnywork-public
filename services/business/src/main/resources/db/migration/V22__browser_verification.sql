CREATE TABLE browser_verification_bindings (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) PRIMARY KEY,
 verifier_id VARCHAR(120) NOT NULL, business_key VARCHAR(200) NOT NULL,
 key_hash VARCHAR(64) NOT NULL, proof_hash VARCHAR(64) NOT NULL,
 UNIQUE(tenant_id,verifier_id,key_hash),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES work_units(tenant_id,id)
);
CREATE TABLE browser_reviewers (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL, principal_id VARCHAR(64) NOT NULL,
 PRIMARY KEY(unit_id,principal_id),
 FOREIGN KEY(tenant_id,unit_id) REFERENCES work_units(tenant_id,id),
 FOREIGN KEY(tenant_id,principal_id) REFERENCES principals(tenant_id,id)
);
CREATE TABLE browser_reconciliations (
 tenant_id VARCHAR(64) NOT NULL, unit_id VARCHAR(64) PRIMARY KEY,
 reviewer_id VARCHAR(64) NOT NULL, result_json TEXT NOT NULL, created_at BIGINT NOT NULL,
 FOREIGN KEY(tenant_id,unit_id) REFERENCES work_units(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewer_id) REFERENCES principals(tenant_id,id)
);
