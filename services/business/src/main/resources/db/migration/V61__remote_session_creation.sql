ALTER TABLE sync_devices ADD COLUMN workspaces_json TEXT;
ALTER TABLE sync_commands ALTER COLUMN session_id DROP NOT NULL;
