-- The existing FunnyWork Host executes maintenance; no external Codex tool is required.
ALTER TABLE maintenance_node_profiles RENAME COLUMN codex_version TO executor_version;
