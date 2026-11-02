CREATE TABLE maintenance_verification_history (
 job_id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 problem_id VARCHAR(64) NOT NULL REFERENCES maintenance_problems(id),
 problem_version INTEGER NOT NULL,
 platform VARCHAR(16) NOT NULL,
 job_json TEXT NOT NULL,
 archived_at BIGINT NOT NULL
);
