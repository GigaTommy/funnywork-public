CREATE TABLE tenant_guards (
  tenant_id VARCHAR(64) PRIMARY KEY,
  max_concurrency INTEGER NOT NULL DEFAULT 64 CHECK (max_concurrency BETWEEN 1 AND 10000)
);
INSERT INTO tenant_guards(tenant_id) SELECT DISTINCT tenant_id FROM principals;
CREATE INDEX tasks_tenant_state_idx ON tasks(tenant_id,state,created_at);
CREATE INDEX nodes_match_idx ON nodes(tenant_id,zone,status,capability,last_seen);
CREATE INDEX attempts_expiry_idx ON attempts(state,expires_at);

CREATE TABLE node_dispatch (
  id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(64) NOT NULL REFERENCES nodes(id),
  attempt_id VARCHAR(64) NOT NULL UNIQUE REFERENCES attempts(id),
  state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','DELIVERED','ACKED','CANCELLED')),
  deliveries INTEGER NOT NULL DEFAULT 0,
  available_at BIGINT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE INDEX dispatch_delivery_idx ON node_dispatch(node_id,state,available_at,created_at);
CREATE TABLE node_inbox (
  message_id VARCHAR(64) PRIMARY KEY REFERENCES node_dispatch(id),
  node_id VARCHAR(64) NOT NULL REFERENCES nodes(id),
  acknowledged_at BIGINT NOT NULL
);

-- Existing active assignments receive one durable notification during upgrade.
INSERT INTO node_dispatch(id,tenant_id,node_id,attempt_id,state,available_at,created_at)
SELECT a.id,n.tenant_id,a.node_id,a.id,'PENDING',a.created_at,a.created_at
FROM attempts a JOIN nodes n ON n.id=a.node_id WHERE a.state IN ('RESERVED','RUNNING');
