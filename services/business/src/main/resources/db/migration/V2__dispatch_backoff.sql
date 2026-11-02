ALTER TABLE work_units ADD COLUMN next_dispatch_at BIGINT NOT NULL DEFAULT 0;
CREATE INDEX units_dispatch_idx ON work_units(state,next_dispatch_at,task_id,ordinal);
