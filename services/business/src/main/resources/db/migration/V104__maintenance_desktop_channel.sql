CREATE TABLE maintenance_desktop_bindings (
 device_id VARCHAR(64) PRIMARY KEY REFERENCES sync_devices(id), tenant_id VARCHAR(64) NOT NULL,
 node_id VARCHAR(64) NOT NULL UNIQUE REFERENCES nodes(id), enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE maintenance_desktop_attempts (
 attempt_id VARCHAR(64) PRIMARY KEY REFERENCES attempts(id), device_id VARCHAR(64) NOT NULL REFERENCES sync_devices(id),
 tenant_id VARCHAR(64) NOT NULL, runtime_epoch VARCHAR(64) NOT NULL
);
