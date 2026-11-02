ALTER TABLE recovery_evidence ADD CONSTRAINT recovery_evidence_tenant_identity UNIQUE(tenant_id,id);
CREATE TABLE recovery_evidence_verification (
 tenant_id VARCHAR(64) NOT NULL,
 evidence_id VARCHAR(64) NOT NULL,
 issuer_id VARCHAR(64) NOT NULL,
 key_sha256 VARCHAR(64) NOT NULL,
 policy_sha256 VARCHAR(64) NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL,
 asserted_outcome VARCHAR(24) NOT NULL,
 issued_at BIGINT NOT NULL,
 expires_at BIGINT NOT NULL,
 verified_at BIGINT NOT NULL,
 verified_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,evidence_id),
 FOREIGN KEY(tenant_id,evidence_id) REFERENCES recovery_evidence(tenant_id,id),
 FOREIGN KEY(tenant_id,verified_by) REFERENCES principals(tenant_id,id)
);
