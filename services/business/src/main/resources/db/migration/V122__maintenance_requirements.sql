CREATE TABLE maintenance_requirements (
 id VARCHAR(64) PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 repository_id VARCHAR(64) NOT NULL REFERENCES maintenance_repositories(id),
 intake_id VARCHAR(64) UNIQUE REFERENCES maintenance_intakes(id),
 origin VARCHAR(20) NOT NULL,
 state VARCHAR(32) NOT NULL,
 version INTEGER NOT NULL,
 title VARCHAR(160) NOT NULL,
 priority VARCHAR(8) NOT NULL,
 spec_json TEXT NOT NULL,
 created_by VARCHAR(64) NOT NULL REFERENCES principals(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE INDEX maintenance_requirement_page ON maintenance_requirements(tenant_id,created_at,id);
CREATE TABLE maintenance_requirement_revisions (
 requirement_id VARCHAR(64) NOT NULL REFERENCES maintenance_requirements(id),
 version INTEGER NOT NULL,
 spec_json TEXT NOT NULL,
 title VARCHAR(160) NOT NULL,
 priority VARCHAR(8) NOT NULL,
 author_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 reason VARCHAR(1000) NOT NULL,
 created_at BIGINT NOT NULL,
 PRIMARY KEY(requirement_id,version)
);
CREATE TABLE maintenance_dependency_maps (
 repository_id VARCHAR(64) PRIMARY KEY REFERENCES maintenance_repositories(id),
 version INTEGER NOT NULL,
 components_json TEXT NOT NULL,
 updated_at BIGINT NOT NULL
);
CREATE TABLE maintenance_requirement_impacts (
 id VARCHAR(64) PRIMARY KEY,
 requirement_id VARCHAR(64) NOT NULL REFERENCES maintenance_requirements(id),
 requirement_version INTEGER NOT NULL,
 input_hash VARCHAR(64) NOT NULL,
 result_json TEXT NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE(requirement_id,requirement_version,input_hash)
);
CREATE TABLE maintenance_requirement_links (
 requirement_id VARCHAR(64) NOT NULL REFERENCES maintenance_requirements(id),
 requirement_version INTEGER NOT NULL,
 problem_id VARCHAR(64) NOT NULL REFERENCES maintenance_problems(id),
 PRIMARY KEY(requirement_id,requirement_version,problem_id)
);
CREATE TABLE maintenance_requirement_requests (
 tenant_id VARCHAR(64) NOT NULL,
 principal_id VARCHAR(64) NOT NULL REFERENCES principals(id),
 request_key VARCHAR(64) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 requirement_id VARCHAR(64) NOT NULL REFERENCES maintenance_requirements(id),
 PRIMARY KEY(tenant_id,principal_id,request_key)
);
