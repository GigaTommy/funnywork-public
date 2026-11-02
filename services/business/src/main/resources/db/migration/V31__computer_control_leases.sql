CREATE TABLE computer_sessions (
 tenant_id VARCHAR(64) NOT NULL, node_id VARCHAR(64) NOT NULL, os_session VARCHAR(10) NOT NULL,
 generation BIGINT NOT NULL, current_attempt VARCHAR(64),
 PRIMARY KEY(tenant_id,node_id,os_session),
 FOREIGN KEY(tenant_id,node_id) REFERENCES nodes(tenant_id,id),
 FOREIGN KEY(tenant_id,current_attempt,node_id) REFERENCES attempts(tenant_id,id,node_id),
 CHECK(generation>=0)
);
CREATE TABLE computer_controls (
 tenant_id VARCHAR(64) NOT NULL, attempt_id VARCHAR(64) PRIMARY KEY, node_id VARCHAR(64) NOT NULL,
 os_session VARCHAR(10) NOT NULL, epoch BIGINT NOT NULL, generation BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL, expires_at BIGINT NOT NULL,
 FOREIGN KEY(tenant_id,attempt_id,node_id) REFERENCES attempts(tenant_id,id,node_id),
 FOREIGN KEY(tenant_id,node_id,os_session) REFERENCES computer_sessions(tenant_id,node_id,os_session),
 CHECK(epoch>0 AND generation>0), CHECK(state IN ('ACTIVE','RELEASED'))
);
