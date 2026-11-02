CREATE TABLE execution_projects (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, zone VARCHAR(64) NOT NULL,
 name VARCHAR(120) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE execution_pools (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, zone VARCHAR(64) NOT NULL,
 name VARCHAR(120) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE execution_profiles (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, zone VARCHAR(64) NOT NULL,
 name VARCHAR(120) NOT NULL, action VARCHAR(16) NOT NULL, platforms_json TEXT NOT NULL,
 assurance VARCHAR(40) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE execution_pool_memberships (
 pool_id VARCHAR(64) NOT NULL REFERENCES execution_pools(id), device_id VARCHAR(64) NOT NULL REFERENCES sync_devices(id),
 tenant_id VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
 PRIMARY KEY(pool_id,device_id)
);
CREATE TABLE execution_resource_grants (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL REFERENCES execution_projects(id),
 repository_id VARCHAR(64) NOT NULL REFERENCES maintenance_repositories(id), pool_id VARCHAR(64) NOT NULL REFERENCES execution_pools(id),
 profile_id VARCHAR(64) NOT NULL REFERENCES execution_profiles(id), device_id VARCHAR(64) NOT NULL DEFAULT '',
 effect VARCHAR(8) NOT NULL, enabled BOOLEAN NOT NULL, expires_at BIGINT NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
 UNIQUE(tenant_id,project_id,repository_id,pool_id,profile_id,device_id)
);
CREATE TABLE execution_routes (
 repository_id VARCHAR(64) NOT NULL REFERENCES maintenance_repositories(id), action VARCHAR(16) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL REFERENCES execution_projects(id),
 pool_id VARCHAR(64) NOT NULL REFERENCES execution_pools(id), profile_id VARCHAR(64) NOT NULL REFERENCES execution_profiles(id),
 enabled BOOLEAN NOT NULL, revision INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(repository_id,action)
);
CREATE TABLE execution_placements (
 attempt_id VARCHAR(64) PRIMARY KEY REFERENCES attempts(id), tenant_id VARCHAR(64) NOT NULL,
 task_id VARCHAR(64) NOT NULL REFERENCES tasks(id), node_id VARCHAR(64) NOT NULL REFERENCES nodes(id),
 device_id VARCHAR(64) NOT NULL REFERENCES sync_devices(id), runtime_epoch VARCHAR(64) NOT NULL,
 repository_id VARCHAR(64) NOT NULL, action VARCHAR(16) NOT NULL, authorization_hash VARCHAR(64) NOT NULL,
 authorization_json TEXT NOT NULL, epoch BIGINT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE execution_writers (
 repository_id VARCHAR(64) PRIMARY KEY REFERENCES maintenance_repositories(id), tenant_id VARCHAR(64) NOT NULL,
 attempt_id VARCHAR(64) NOT NULL REFERENCES attempts(id), state VARCHAR(16) NOT NULL
);
CREATE TABLE execution_upload_authorizations (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, attempt_id VARCHAR(64) NOT NULL REFERENCES execution_placements(attempt_id),
 digest VARCHAR(64) NOT NULL, max_bytes INTEGER NOT NULL, spec_hash VARCHAR(64) NOT NULL,
 expires_at BIGINT NOT NULL, state VARCHAR(16) NOT NULL, content_base64 TEXT,
 UNIQUE(attempt_id,digest)
);
CREATE INDEX execution_member_device ON execution_pool_memberships(tenant_id,device_id,enabled,pool_id);
CREATE INDEX execution_grant_lookup ON execution_resource_grants(tenant_id,repository_id,pool_id,profile_id,enabled);
CREATE INDEX execution_placement_task ON execution_placements(tenant_id,task_id);
