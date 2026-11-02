-- Local assignments remain usable without an external identity provider. No historical backfill.
CREATE TABLE task_member_departments (
 principal_id VARCHAR(64) NOT NULL REFERENCES principals(id), tenant_id VARCHAR(64) NOT NULL,
 zone VARCHAR(64) NOT NULL, department_id VARCHAR(64) NOT NULL DEFAULT '',
 version INTEGER NOT NULL, changed_at BIGINT NOT NULL, changed_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(principal_id,zone)
);
CREATE TABLE task_creation_attribution (
 task_id VARCHAR(64) PRIMARY KEY REFERENCES tasks(id), tenant_id VARCHAR(64) NOT NULL,
 department_id VARCHAR(64) NOT NULL, department_name VARCHAR(120) NOT NULL,
 department_basis VARCHAR(40) NOT NULL, member_version INTEGER NOT NULL,
 project_id VARCHAR(64) NOT NULL, project_name VARCHAR(200) NOT NULL, project_basis VARCHAR(40) NOT NULL,
 captured_at BIGINT NOT NULL
);
