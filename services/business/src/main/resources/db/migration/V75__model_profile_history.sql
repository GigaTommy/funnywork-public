CREATE TABLE model_profile_history (
 profile_id VARCHAR(64) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 version INTEGER NOT NULL,
 zone VARCHAR(64) NOT NULL,
 name VARCHAR(120) NOT NULL,
 model VARCHAR(120) NOT NULL,
 base_url VARCHAR(1000) NOT NULL,
 require_token BOOLEAN NOT NULL,
 enabled BOOLEAN NOT NULL,
 actor_id VARCHAR(64),
 updated_at BIGINT NOT NULL,
 restored_from INTEGER,
 origin VARCHAR(16) NOT NULL,
 PRIMARY KEY(profile_id,version)
);
CREATE INDEX model_profile_history_scope ON model_profile_history(tenant_id,profile_id,zone,version);
INSERT INTO model_profile_history(profile_id,tenant_id,version,zone,name,model,base_url,require_token,enabled,updated_at,origin)
 SELECT id,tenant_id,version,zone,name,model,base_url,require_token,enabled,updated_at,'MIGRATED' FROM model_profiles;
