-- Annual reporting shares physical nodes and execution placements, without a repository identity.
CREATE TABLE node_capabilities (
 node_id VARCHAR(64) NOT NULL REFERENCES nodes(id), capability VARCHAR(64) NOT NULL,
 PRIMARY KEY(node_id,capability)
);
CREATE TABLE annual_datasets (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, owner_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 zone VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL REFERENCES execution_projects(id),
 name VARCHAR(120) NOT NULL, report_year INTEGER NOT NULL, materials_json TEXT NOT NULL,
 snapshot_hash VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, revision INTEGER NOT NULL DEFAULT 1,
 agent_id VARCHAR(64) NOT NULL REFERENCES agent_versions(id), created_at BIGINT NOT NULL
);
CREATE TABLE execution_dataset_routes (
 repository_id VARCHAR(64) NOT NULL REFERENCES annual_datasets(id), action VARCHAR(16) NOT NULL,
 tenant_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL REFERENCES execution_projects(id),
 pool_id VARCHAR(64) NOT NULL REFERENCES execution_pools(id), profile_id VARCHAR(64) NOT NULL REFERENCES execution_profiles(id),
 enabled BOOLEAN NOT NULL, revision INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(repository_id,action)
);
CREATE TABLE execution_dataset_grants (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL REFERENCES execution_projects(id),
 repository_id VARCHAR(64) NOT NULL REFERENCES annual_datasets(id), pool_id VARCHAR(64) NOT NULL REFERENCES execution_pools(id),
 profile_id VARCHAR(64) NOT NULL REFERENCES execution_profiles(id), device_id VARCHAR(64) NOT NULL DEFAULT '',
 effect VARCHAR(8) NOT NULL, enabled BOOLEAN NOT NULL, expires_at BIGINT NOT NULL, revision INTEGER NOT NULL DEFAULT 1,
 UNIQUE(tenant_id,project_id,repository_id,pool_id,profile_id,device_id)
);
CREATE VIEW execution_all_routes AS SELECT * FROM execution_routes UNION ALL SELECT * FROM execution_dataset_routes;
CREATE VIEW execution_all_grants AS SELECT * FROM execution_resource_grants UNION ALL SELECT * FROM execution_dataset_grants;
CREATE TABLE annual_report_jobs (
 task_id VARCHAR(64) PRIMARY KEY REFERENCES tasks(id), unit_id VARCHAR(64) NOT NULL REFERENCES work_units(id),
 tenant_id VARCHAR(64) NOT NULL, dataset_id VARCHAR(64) NOT NULL REFERENCES annual_datasets(id),
 platform VARCHAR(16) NOT NULL, spec_json TEXT NOT NULL, spec_hash VARCHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL, candidate_json TEXT, receipt_hash VARCHAR(64), artifact_id VARCHAR(64),
 evidence_json TEXT, created_at BIGINT NOT NULL, UNIQUE(dataset_id)
);
CREATE INDEX execution_dataset_grant_lookup ON execution_dataset_grants(tenant_id,repository_id,pool_id,profile_id,enabled);
