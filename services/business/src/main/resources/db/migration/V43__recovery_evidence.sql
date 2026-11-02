ALTER TABLE recovery_cases ADD CONSTRAINT recovery_cases_tenant_identity UNIQUE(tenant_id,id);
CREATE TABLE recovery_evidence (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 case_id VARCHAR(64) NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 kind VARCHAR(32) NOT NULL CHECK(kind IN ('OPERATOR_NOTE','NODE_COMPARISON','STOP_ATTESTATION','BUSINESS_RECEIPT')),
 content_sha256 VARCHAR(64) NOT NULL,
 content_text TEXT NOT NULL,
 submitted_by VARCHAR(64) NOT NULL,
 expected_version BIGINT NOT NULL CHECK(expected_version>=0),
 accepted_version BIGINT NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE(tenant_id,case_id,request_key),
 UNIQUE(tenant_id,case_id,accepted_version),
 FOREIGN KEY(tenant_id,case_id) REFERENCES recovery_cases(tenant_id,id),
 FOREIGN KEY(tenant_id,submitted_by) REFERENCES principals(tenant_id,id),
 CHECK(accepted_version=expected_version+1)
);
