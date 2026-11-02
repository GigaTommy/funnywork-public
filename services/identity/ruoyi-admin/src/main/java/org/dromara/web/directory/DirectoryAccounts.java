package org.dromara.web.directory;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.vo.SysUserVo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
public class DirectoryAccounts {
    private final JdbcTemplate db;
    private final DirectoryRegistry registry;
    private record Verification(String tenant,long user,long version) {}
    private final ThreadLocal<Verification> verified=new ThreadLocal<>();
    public DirectoryAccounts(JdbcTemplate db,DirectoryRegistry registry){this.db=db;this.registry=registry;}
    public boolean password(SysUserVo user,String password){
        verified.remove();
        var source=registry.source(user.getTenantId(),user.getExternalSource());
        var rows=db.queryForList("select external_id,remote_enabled,remote_version from fw_directory_users where tenant_id=? and source_id=? and user_id=?",user.getTenantId(),source.id(),user.getUserId());
        if(rows.size()!=1||!Boolean.TRUE.equals(rows.get(0).get("remote_enabled")))return false;
        String subject=registry.adapter(source).authenticate(user.getUserName(),password);
        if(subject==null||!Objects.equals(subject,rows.get(0).get("external_id")))return false;
        verified.set(new Verification(user.getTenantId(),user.getUserId(),((Number)rows.get(0).get("remote_version")).longValue()));
        return true;
    }
    public void requireVerified(SysUserVo user){
        if(user.getExternalSource()==null)return;
        var proof=verified.get();
        if(proof==null||!proof.tenant().equals(user.getTenantId())||proof.user()!=user.getUserId())throw new ServiceException("该账号需要通过统一身份平台认证",401);
        requireActive(user.getTenantId(),user.getUserId(),user.getExternalSource(),proof.version());
        var current=db.queryForMap("select user_name,dept_id from sys_user where tenant_id=? and user_id=?",user.getTenantId(),user.getUserId());
        if(!Objects.equals(current.get("user_name"),user.getUserName())||!Objects.equals(((Number)current.get("dept_id")).longValue(),user.getDeptId()))
            throw new ServiceException("账号资料已更新，请重新登录",401);
    }
    public Long verifiedVersion(){return verified.get()==null?null:verified.get().version();}
    public void clear(){verified.remove();}
    public void requireActive(String tenant,long user,String source,Long version){
        registry.source(tenant,source);
        Integer count=db.queryForObject("select count(*) from sys_user u join fw_directory_users e on e.user_id=u.user_id and e.tenant_id=u.tenant_id where u.tenant_id=? and u.user_id=? and u.external_source=? and e.source_id=? and u.status='0' and u.del_flag='0' and e.remote_enabled=true and e.remote_version=?",Integer.class,tenant,user,source,source,version);
        if(count==null||count!=1)throw new ServiceException("统一身份账号已停用，请重新登录",401);
    }
}
