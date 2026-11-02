CREATE TABLE task_dependencies (
  task_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  predecessor INTEGER NOT NULL CHECK (predecessor BETWEEN 0 AND 99),
  successor INTEGER NOT NULL CHECK (successor BETWEEN 0 AND 99),
  PRIMARY KEY (task_id,predecessor,successor),
  CHECK (predecessor <> successor),
  FOREIGN KEY (tenant_id,task_id) REFERENCES tasks(tenant_id,id) ON DELETE CASCADE,
  FOREIGN KEY (task_id,predecessor) REFERENCES work_units(task_id,ordinal) ON DELETE CASCADE,
  FOREIGN KEY (task_id,successor) REFERENCES work_units(task_id,ordinal) ON DELETE CASCADE
);
CREATE INDEX dependencies_successor_idx ON task_dependencies(task_id,successor,predecessor);
