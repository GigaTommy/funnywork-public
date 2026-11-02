-- Additive repair for the historical 8120 desktop/directory permission collision.
-- Execute in one transaction. Preserve the permission meaning of existing grants.
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=8121 AND perms IS DISTINCT FROM 'funnyworker:directory:write')
 OR EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=8120 AND (perms IS NULL OR perms NOT IN ('funnyworker:directory:write','funnyworker:desktop:read'))) THEN
  RAISE EXCEPTION 'Reserved funnywork menu id is occupied by an unrelated permission';
 END IF;
 INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time)
 VALUES(8121,'组织身份源维护',8020,1,'#','',1,0,'F','0','0','funnyworker:directory:write','#',103,1,now()) ON CONFLICT(menu_id) DO NOTHING;
 IF EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=8120 AND perms='funnyworker:directory:write') THEN
  INSERT INTO sys_role_menu(role_id,menu_id) SELECT role_id,8121 FROM sys_role_menu WHERE menu_id=8120 ON CONFLICT DO NOTHING;
  DELETE FROM sys_role_menu WHERE menu_id=8120;
  UPDATE sys_tenant_package SET menu_ids=trim(both ',' from replace(','||menu_ids||',',',8120,',',8121,'))
   WHERE (','||menu_ids||',') LIKE '%,8120,%';
  DELETE FROM sys_menu WHERE menu_id=8120;
 END IF;
 INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time)
 VALUES(8120,'桌面客户端使用',8008,2,'#','',1,0,'F','0','0','funnyworker:desktop:read','#',103,1,now()) ON CONFLICT(menu_id) DO NOTHING;
END $$;
