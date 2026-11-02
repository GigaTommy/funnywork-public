CREATE TABLE task_artifacts (
 tenant_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL,
 artifact_id VARCHAR(64) NOT NULL, version INTEGER NOT NULL, attached_at BIGINT NOT NULL,
 PRIMARY KEY(task_id,artifact_id,version),
 FOREIGN KEY(tenant_id,task_id) REFERENCES tasks(tenant_id,id),
 FOREIGN KEY(tenant_id,artifact_id) REFERENCES artifacts(tenant_id,id),
 FOREIGN KEY(artifact_id,version) REFERENCES artifact_versions(artifact_id,version)
);
