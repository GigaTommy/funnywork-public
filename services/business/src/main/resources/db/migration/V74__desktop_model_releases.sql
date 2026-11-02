CREATE TABLE desktop_model_releases (
 tenant_id VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL,
 endpoint VARCHAR(1000) NOT NULL,
 model VARCHAR(100) NOT NULL,
 thinking VARCHAR(16) NOT NULL,
 max_tokens INTEGER NOT NULL,
 credential_ref VARCHAR(120) NOT NULL,
 published_by VARCHAR(64) NOT NULL,
 published_at BIGINT NOT NULL,
 restored_from BIGINT,
 origin VARCHAR(16) NOT NULL,
 PRIMARY KEY (tenant_id, version)
);
CREATE TABLE desktop_model_checks (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 actor_id VARCHAR(64) NOT NULL,
 settings_json TEXT NOT NULL,
 check_type VARCHAR(16) NOT NULL,
 outcome VARCHAR(32) NOT NULL,
 http_status INTEGER NOT NULL,
 latency_ms BIGINT NOT NULL,
 observed_at BIGINT NOT NULL
);
CREATE INDEX desktop_model_checks_tenant_time ON desktop_model_checks(tenant_id, observed_at);
