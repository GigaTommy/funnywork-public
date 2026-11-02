CREATE TABLE model_profiles (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 zone VARCHAR(64) NOT NULL,
 name VARCHAR(120) NOT NULL,
 model VARCHAR(120) NOT NULL,
 base_url VARCHAR(1000) NOT NULL,
 require_token BOOLEAN NOT NULL,
 enabled BOOLEAN NOT NULL,
 version INTEGER NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE INDEX model_profiles_scope ON model_profiles(tenant_id,zone,id);
