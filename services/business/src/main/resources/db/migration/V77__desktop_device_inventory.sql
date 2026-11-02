-- Keyset pagination stays tenant-local without reading conversation payloads.
CREATE INDEX sync_devices_management_page ON sync_devices(tenant_id,id);
