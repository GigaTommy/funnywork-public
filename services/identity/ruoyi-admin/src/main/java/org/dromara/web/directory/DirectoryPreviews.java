package org.dromara.web.directory;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.dromara.common.core.exception.ServiceException;
import cn.dev33.satoken.stp.StpUtil;
import java.util.*;

/** Approval applies the stored snapshot, never fetches a different set of remote users. */
@Service
public class DirectoryPreviews {
    private final JdbcTemplate db;private final DirectoryRegistry registry;private final DirectorySync sync;private final DirectoryAccess access;private final TransactionTemplate tx;
    public DirectoryPreviews(JdbcTemplate db,DirectoryRegistry registry,DirectorySync sync,DirectoryAccess access,PlatformTransactionManager manager){this.db=db;this.registry=registry;this.sync=sync;this.access=access;this.tx=new TransactionTemplate(manager);}
    private Map<String,Object> row(String tenant,String source,String id){registry.source(tenant,source);var rows=db.queryForList("select * from fw_directory_previews where id=? and tenant_id=? and source_id=?",id,tenant,source);if(rows.isEmpty())throw new ServiceException("预览不存在或无权查看",404);return rows.get(0);}
    private static Map<String,Object> plan(Map<String,Object> row){try{return DirectoryPreviewPlan.JSON.readValue((String)row.get("plan"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new ServiceException("预览数据不可用，请重新预览",409);}}
    public Map<String,Object> get(String tenant,String source,long actor,String id,int offset,int limit){
        access.require(tenant,actor,false);if(offset<0||limit<1||limit>100)throw new ServiceException("分页参数无效",400);
        var row=row(tenant,source,id);var result=new LinkedHashMap<>(plan(row));
        var items=(List<?>)result.get("items");int start=Math.min(offset,items.size());result.put("items",items.subList(start,Math.min(start+limit,items.size())));result.putIfAbsent("total",items.size());result.remove("baseline");result.put("detailsPurged",row.get("purged"));result.put("id",id);result.put("state",row.get("state"));result.put("expiresAt",row.get("expires_at"));result.put("expired",System.currentTimeMillis()>((Number)row.get("expires_at")).longValue());return result;
    }
    public Map<String,Object> approve(String tenant,String source,long actor,String id){
        access.require(tenant,actor,true);var changed=new ArrayList<Long>();
        var result=tx.execute(status->{
            db.queryForList("select tenant_id from sys_tenant where tenant_id=? for update",tenant);
            var row=row(tenant,source,id);
            if("APPLIED".equals(row.get("state")))return Map.<String,Object>of("runId",id,"state","SUCCEEDED","alreadyApplied",true);
            if(!"READY".equals(row.get("state"))||System.currentTimeMillis()>((Number)row.get("expires_at")).longValue())throw new ServiceException("预览已过期，请重新预览",409);
            if(((Number)row.get("generation")).longValue()!=db.queryForObject("select generation from fw_directory_sources where tenant_id=? and source_id=?",Long.class,tenant,source))throw new ServiceException("同步版本已变化，请重新预览",409);
            var currentSource=registry.source(tenant,source);
            if(!DirectoryPreviewPlan.sourceHash(currentSource).equals(row.get("source_hash")))throw new ServiceException("身份源配置已变化，请重新预览",409);
            List<DirectoryAdapter.User> users;
            try{users=List.of(DirectoryPreviewPlan.JSON.readValue((String)row.get("payload"),DirectoryAdapter.User[].class));}catch(Exception e){throw new ServiceException("预览数据不可用",409);}
            var expected=plan(row);var current=DirectoryPreviewPlan.inspect(db,currentSource,users,true);
            if(((Number)expected.get("conflicts")).intValue()>0)throw new ServiceException("存在冲突，请修正后重新预览",409);
            if(!DirectoryPreviewPlan.json(expected).equals(DirectoryPreviewPlan.json(current)))throw new ServiceException("本地账号或部门已变化，请重新预览",409);
            if(db.update("update fw_directory_runs set state='FETCHING',actor_id=? where id=? and state='PREVIEW'",actor,id)!=1)throw new ServiceException("预览状态已变化，请刷新",409);
            var receipt=sync.apply(currentSource,actor,id,((Number)row.get("generation")).longValue(),(String)row.get("checkpoint"),users,changed);
            db.update("update fw_directory_previews set state='APPLIED',approved_by=?,applied_at=?,payload='' where id=?",actor,System.currentTimeMillis(),id);
            return receipt;
        });
        boolean invalidated=true;for(long user:changed)try{StpUtil.logout("sys_user:"+user);}catch(RuntimeException unavailable){invalidated=false;}
        var response=new LinkedHashMap<>(result);response.put("sessionsInvalidated",invalidated);return response;
    }
    void expire(){
        for(var row:db.queryForList("select * from fw_directory_previews where expires_at<? and purged=false order by expires_at limit 100",System.currentTimeMillis())){
            var summary=new LinkedHashMap<>(plan(row));summary.put("total",((List<?>)summary.get("items")).size());summary.put("items",List.of());summary.remove("baseline");
            db.update("update fw_directory_previews set state=case when state='READY' then 'EXPIRED' else state end,payload='',plan=?,purged=true where id=? and purged=false",DirectoryPreviewPlan.json(summary),row.get("id"));
        }
    }
}
