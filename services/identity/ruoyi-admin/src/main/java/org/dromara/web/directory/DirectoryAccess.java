package org.dromara.web.directory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.dromara.common.core.exception.ServiceException;

/** Explicit directory permission must come from a tenant-wide role. */
@Service
public class DirectoryAccess {
    private final JdbcTemplate db;
    public DirectoryAccess(JdbcTemplate db){this.db=db;}
    public boolean allowed(String tenant,long actor,boolean write){
        if(db.queryForObject("select count(*) from sys_user where tenant_id=? and user_id=? and status='0' and del_flag='0'",Integer.class,tenant,actor)!=1)return false;
        if(actor==1)return true;
        return db.queryForObject("select count(*) from sys_user_role ur join sys_role r on r.role_id=ur.role_id join sys_role_menu rm on rm.role_id=r.role_id join sys_menu m on m.menu_id=rm.menu_id where ur.user_id=? and r.tenant_id=? and r.status='0' and r.del_flag='0' and r.data_scope='1' and m.status='0' and m.perms=?",Integer.class,actor,tenant,"funnyworker:directory:"+(write?"write":"read"))>0;
    }
    public void require(String tenant,long actor,boolean write){if(!allowed(tenant,actor,write))throw new ServiceException("需要组织范围的身份源管理权限",403);}
}
