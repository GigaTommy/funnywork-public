ALTER TABLE maintenance_intakes ADD COLUMN last_checked_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE maintenance_problems ADD COLUMN last_checked_at BIGINT NOT NULL DEFAULT 0;
ALTER TABLE maintenance_intake_sources ADD COLUMN client_version VARCHAR(80) NOT NULL DEFAULT '';
CREATE INDEX maintenance_intake_fair_queue ON maintenance_intakes(repository_id,state,last_checked_at,created_at);
CREATE INDEX maintenance_problem_fair_queue ON maintenance_problems(repository_id,state,last_checked_at,created_at);
CREATE TABLE maintenance_source_failures (
 tenant_id VARCHAR(64) NOT NULL,
 repository_id VARCHAR(64) NOT NULL REFERENCES maintenance_repositories(id),
 source_key VARCHAR(200) NOT NULL,
 source_revision VARCHAR(64) NOT NULL,
 payload_json TEXT NOT NULL,
 error_code VARCHAR(80) NOT NULL,
 resolved BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(repository_id,source_key,source_revision)
);
CREATE TABLE maintenance_feedback_messages (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 intake_id VARCHAR(64) NOT NULL REFERENCES maintenance_intakes(id),
 author_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 request_id VARCHAR(64) NOT NULL,
 content TEXT NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE(intake_id,author_id,request_id)
);
