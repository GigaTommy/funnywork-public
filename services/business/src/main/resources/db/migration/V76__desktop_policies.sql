CREATE TABLE desktop_policy_releases (
 tenant_id VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL,
 policy_json TEXT NOT NULL,
 digest VARCHAR(64) NOT NULL,
 actor_id VARCHAR(64) NOT NULL,
 created_at BIGINT NOT NULL,
 restored_from BIGINT,
 PRIMARY KEY(tenant_id,version)
);
CREATE TABLE desktop_policy_receipts (
 tenant_id VARCHAR(64) NOT NULL,
 principal_id VARCHAR(64) NOT NULL,
 device_id VARCHAR(64) NOT NULL,
 issued_version BIGINT NOT NULL,
 issued_digest VARCHAR(64) NOT NULL,
 issued_at BIGINT NOT NULL,
 applied_version BIGINT,
 applied_digest VARCHAR(64),
 applied_at BIGINT,
 client_version VARCHAR(80),
 schema_version INTEGER,
 preferences_json TEXT,
 effective_json TEXT,
 PRIMARY KEY(tenant_id,principal_id,device_id)
);
