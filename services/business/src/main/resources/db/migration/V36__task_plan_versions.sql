ALTER TABLE tasks ADD COLUMN plan_version INTEGER NOT NULL DEFAULT 1 CHECK (plan_version BETWEEN 1 AND 100);
CREATE TABLE task_plans (
  task_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  version INTEGER NOT NULL CHECK (version BETWEEN 1 AND 100),
  input_count INTEGER NOT NULL CHECK (input_count BETWEEN 0 AND 100),
  request_hash VARCHAR(64) NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (task_id,version),
  FOREIGN KEY (tenant_id,task_id) REFERENCES tasks(tenant_id,id) ON DELETE CASCADE
);
INSERT INTO task_plans(task_id,tenant_id,version,input_count,request_hash,created_at)
SELECT t.id,t.tenant_id,1,(SELECT count(*) FROM work_units u WHERE u.task_id=t.id),t.request_hash,t.created_at FROM tasks t;
ALTER TABLE work_units ADD COLUMN plan_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE work_units ADD CONSTRAINT units_plan_version FOREIGN KEY (task_id,plan_version) REFERENCES task_plans(task_id,version);
