CREATE TABLE sync_device_links (
 id VARCHAR(64) PRIMARY KEY,
 device_id VARCHAR(64) NOT NULL REFERENCES sync_devices(id),
 tenant_id VARCHAR(128) NOT NULL,
 principal_id VARCHAR(128) NOT NULL,
 secret_hash VARCHAR(64) NOT NULL,
 epoch VARCHAR(64) NOT NULL,
 access_revision BIGINT NOT NULL,
 expires_at BIGINT NOT NULL,
 confirmed_at BIGINT
);
CREATE UNIQUE INDEX sync_device_link_source ON sync_device_links(device_id);
