ALTER TABLE attempts ADD COLUMN recovery_generation VARCHAR(64) NOT NULL DEFAULT 'initial';
CREATE INDEX attempts_recovery_idx ON attempts(tenant_id,recovery_generation,state);
