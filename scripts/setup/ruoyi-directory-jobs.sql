-- Additive migration for durable identity synchronization jobs and schedules.
CREATE TABLE IF NOT EXISTS fw_directory_jobs (
 id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL,
 actor_id BIGINT NOT NULL, full_sync BOOLEAN NOT NULL, state VARCHAR(16) NOT NULL,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, due_at BIGINT NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, run_id VARCHAR(36), error_code VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS fw_directory_jobs_due_idx ON fw_directory_jobs(state,due_at);
CREATE TABLE IF NOT EXISTS fw_directory_schedules (
 tenant_id VARCHAR(20) NOT NULL, source_id VARCHAR(64) NOT NULL, actor_id BIGINT NOT NULL,
 interval_seconds INTEGER NOT NULL, enabled BOOLEAN NOT NULL, next_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,source_id)
);
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time)
VALUES (8020,'身份源',8000,20,'identity-sources','funnyworker/identity-sources',1,0,'C','0','0','funnyworker:directory:read','user',103,1,now()) ON CONFLICT (menu_id) DO NOTHING;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time)
VALUES (8120,'组织身份源维护',8020,1,'#','',1,0,'F','0','0','funnyworker:directory:write','#',103,1,now()) ON CONFLICT (menu_id) DO NOTHING;
