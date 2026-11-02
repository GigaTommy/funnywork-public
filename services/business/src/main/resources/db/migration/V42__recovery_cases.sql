CREATE TABLE recovery_cases (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 attempt_id VARCHAR(64) NOT NULL,
 recovery_generation VARCHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL CHECK (state IN ('NEEDS_ATTENTION','IN_REVIEW')),
 version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
 claimed_by VARCHAR(64),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 UNIQUE(tenant_id,recovery_generation,attempt_id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES attempts(tenant_id,id),
 FOREIGN KEY(tenant_id,claimed_by) REFERENCES principals(tenant_id,id),
 CHECK ((state='NEEDS_ATTENTION' AND claimed_by IS NULL) OR (state='IN_REVIEW' AND claimed_by IS NOT NULL))
);
CREATE INDEX recovery_cases_page_idx ON recovery_cases(tenant_id,recovery_generation,id);
