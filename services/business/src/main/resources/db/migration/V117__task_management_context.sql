-- Business classification is explicit; historical tasks are not assigned from current identity departments.
CREATE TABLE task_departments (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, zone VARCHAR(64) NOT NULL,
 name VARCHAR(120) NOT NULL, created_at BIGINT NOT NULL, UNIQUE(tenant_id,zone,name)
);
CREATE TABLE task_management_context (
 task_id VARCHAR(64) PRIMARY KEY REFERENCES tasks(id), tenant_id VARCHAR(64) NOT NULL,
 department_id VARCHAR(64) NOT NULL DEFAULT '', project_id VARCHAR(64) NOT NULL DEFAULT '',
 basis VARCHAR(40) NOT NULL, version INTEGER NOT NULL, changed_at BIGINT NOT NULL, changed_by VARCHAR(64) NOT NULL
);
CREATE TABLE management_task_limits (
 principal_id VARCHAR(64) PRIMARY KEY REFERENCES principals(id), tenant_id VARCHAR(64) NOT NULL,
 restrict_departments BOOLEAN NOT NULL, department_ids TEXT NOT NULL,
 restrict_projects BOOLEAN NOT NULL, project_ids TEXT NOT NULL, version INTEGER NOT NULL
);
CREATE TABLE management_task_commands (
 id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, actor_id VARCHAR(64) NOT NULL,
 task_id VARCHAR(64) NOT NULL REFERENCES tasks(id), request_key VARCHAR(120) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, action VARCHAR(16) NOT NULL, result_json TEXT NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(tenant_id,actor_id,request_key)
);
CREATE INDEX task_context_department ON task_management_context(tenant_id,department_id,task_id);
CREATE INDEX task_context_project ON task_management_context(tenant_id,project_id,task_id);
