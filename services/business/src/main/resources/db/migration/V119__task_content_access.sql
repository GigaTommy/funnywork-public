-- Metadata access never implicitly grants employee input or artifact access.
CREATE TABLE management_task_content_access (
 principal_id VARCHAR(64) PRIMARY KEY REFERENCES principals(id), tenant_id VARCHAR(64) NOT NULL,
 read_input BOOLEAN NOT NULL DEFAULT FALSE, read_artifacts BOOLEAN NOT NULL DEFAULT FALSE,
 version INTEGER NOT NULL, changed_at BIGINT NOT NULL, changed_by VARCHAR(64) NOT NULL
);
