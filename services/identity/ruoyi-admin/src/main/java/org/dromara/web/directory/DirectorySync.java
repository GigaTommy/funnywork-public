package org.dromara.web.directory;

import cn.hutool.core.util.IdUtil;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Fetch first, then apply a complete validated snapshot atomically. Never infer deletions from absence. */
@Service
public class DirectorySync {
    private final JdbcTemplate db;
    private final DirectoryRegistry registry;
    private final TransactionTemplate transaction;
    public DirectorySync(JdbcTemplate db,DirectoryRegistry registry,PlatformTransactionManager transactions){this.db=db;this.registry=registry;this.transaction=new TransactionTemplate(transactions);}
    public Map<String,Object> sync(String tenant,String sourceId,long actor,boolean full){
        return sync(tenant,sourceId,actor,full,UUID.randomUUID().toString());
    }
    Map<String,Object> sync(String tenant,String sourceId,long actor,boolean full,String run){
        return sync(tenant,sourceId,actor,full,run,false);
    }
    Map<String,Object> sync(String tenant,String sourceId,long actor,boolean full,String run,boolean preview){
        var source=registry.source(tenant,sourceId);
        var state=transaction.execute(status->{
            lock(tenant);
            db.update("update fw_directory_runs set state='FAILED',completed_at=?,error_code='SYNC_INTERRUPTED' where tenant_id=? and source_id=? and state='FETCHING' and created_at<?",System.currentTimeMillis(),tenant,sourceId,System.currentTimeMillis()-180_000);
            var rows=db.queryForList("select * from fw_directory_sources where tenant_id=? and source_id=?",tenant,sourceId);
            if(rows.isEmpty())db.update("insert into fw_directory_sources(tenant_id,source_id,checkpoint,generation) values(?,?,'',0)",tenant,sourceId);
            var current=db.queryForMap("select * from fw_directory_sources where tenant_id=? and source_id=?",tenant,sourceId);
            db.update("insert into fw_directory_runs(id,tenant_id,source_id,actor_id,mode,state,generation,created_at) values(?,?,?,?,?,'FETCHING',?,?)",run,tenant,sourceId,actor,full?"FULL":"INCREMENTAL",current.get("generation"),System.currentTimeMillis());
            return current;
        });
        try {
            String checkpoint=full?"":(String)state.get("checkpoint"),cursor="",snapshot=null,nextCheckpoint=null;
            Set<String> cursors=new HashSet<>(),subjects=new HashSet<>(),names=new HashSet<>();
            List<DirectoryAdapter.User> users=new ArrayList<>();
            var adapter=registry.adapter(source);
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(120).toNanos();
            do {
                if(System.nanoTime()>deadline||!cursors.add(cursor)||cursors.size()>100)throw failure("SYNC_PAGE_LIMIT");
                var page=adapter.users(checkpoint,cursor);
                if(page==null||!bounded(page.snapshot(),256)||page.nextCursor()==null||page.nextCursor().length()>2048||!bounded(page.checkpoint(),2048)||page.users()==null||page.users().size()>500)throw failure("SYNC_INVALID_PAGE");
                if(snapshot==null){snapshot=page.snapshot();nextCheckpoint=page.checkpoint();}
                else if(!snapshot.equals(page.snapshot())||!nextCheckpoint.equals(page.checkpoint()))throw failure("SYNC_SNAPSHOT_CHANGED");
                for(var user:page.users()){
                    validate(user);
                    if(!subjects.add(user.externalId())||!names.add(user.username()))throw failure("SYNC_DUPLICATE_USER");
                    users.add(user);
                    if(users.size()>10000)throw failure("SYNC_USER_LIMIT");
                }
                cursor=page.nextCursor();
            }while(!cursor.isEmpty());
            if(full&&users.isEmpty())throw failure("SYNC_EMPTY_SNAPSHOT");
            String finalCheckpoint=nextCheckpoint;
            if(preview){
                return transaction.execute(status->{
                    lock(tenant);
                    long generation=((Number)state.get("generation")).longValue();
                    if(generation!=db.queryForObject("select generation from fw_directory_sources where tenant_id=? and source_id=?",Long.class,tenant,sourceId))throw failure("SYNC_VERSION_CONFLICT");
                    if(!"FETCHING".equals(db.queryForObject("select state from fw_directory_runs where id=?",String.class,run)))throw failure("SYNC_INTERRUPTED");
                    var plan=DirectoryPreviewPlan.inspect(db,source,users);
                    long now=System.currentTimeMillis();
                    db.update("insert into fw_directory_previews(id,tenant_id,source_id,generation,checkpoint,payload,plan,source_hash,state,created_at,expires_at) values(?,?,?,?,?,?,?,?,'READY',?,?)",run,tenant,sourceId,generation,finalCheckpoint,DirectoryPreviewPlan.json(users),DirectoryPreviewPlan.json(plan),DirectoryPreviewPlan.sourceHash(source),now,now+86_400_000L);
                    db.update("update fw_directory_runs set state='PREVIEW',completed_at=? where id=?",now,run);
                    return Map.<String,Object>of("runId",run,"state","PREVIEW");
                });
            }
            var changed=new ArrayList<Long>();
            var result=transaction.execute(status->apply(source,actor,run,((Number)state.get("generation")).longValue(),finalCheckpoint,users,changed));
            // Live request guards also enforce source status and version if eviction is delayed.
            boolean invalidated=true;
            for(long id:changed)try{StpUtil.logout("sys_user:"+id);}catch(RuntimeException unavailable){invalidated=false;}
            var response=new LinkedHashMap<String,Object>(result);
            response.put("sessionsInvalidated",invalidated);
            return response;
        }catch(RuntimeException e){
            String code=e instanceof SyncFailure failure?failure.code:"SYNC_FAILED";
            db.update("update fw_directory_runs set state='FAILED',completed_at=?,error_code=? where id=? and state='FETCHING'",System.currentTimeMillis(),code,run);
            throw new ServiceException("用户同步未完成，批次 "+run+"，错误 "+code,409);
        }
    }
    Map<String,Object> apply(DirectoryRegistry.Source source,long actor,String run,long generation,String checkpoint,List<DirectoryAdapter.User> users,List<Long> changed){
        String tenant=source.tenantId();lock(tenant);
        long current=db.queryForObject("select generation from fw_directory_sources where tenant_id=? and source_id=?",Long.class,tenant,source.id());
        if(current!=generation)throw failure("SYNC_VERSION_CONFLICT");
        if(!"FETCHING".equals(db.queryForObject("select state from fw_directory_runs where id=?",String.class,run)))throw failure("SYNC_INTERRUPTED");
        int added=0,updated=0,skipped=0;
        for(var user:users){
            Long department=source.departments().get(user.departmentId());
            if(department==null||db.queryForObject("select count(*) from sys_dept where tenant_id=? and dept_id=? and status='0' and del_flag='0'",Integer.class,tenant,department)!=1)throw failure("SYNC_DEPARTMENT_UNMAPPED");
            String hash=hash(user);
            var links=db.queryForList("select * from fw_directory_users where tenant_id=? and source_id=? and external_id=?",tenant,source.id(),user.externalId());
            Long userId=links.isEmpty()?null:((Number)links.get(0).get("user_id")).longValue();
            if(userId!=null){
                var old=links.get(0);long version=((Number)old.get("remote_version")).longValue();
                if(user.version()<version){skipped++;continue;}
                if(user.version()==version){if(!hash.equals(old.get("content_hash")))throw failure("SYNC_USER_VERSION_CONFLICT");skipped++;continue;}
            }
            var conflicts=db.queryForList("select user_id from sys_user where tenant_id=? and user_name=? and del_flag='0'",tenant,user.username());
            for(var row:conflicts)if(!Objects.equals(userId,((Number)row.get("user_id")).longValue()))throw failure("SYNC_USERNAME_CONFLICT");
            if(userId==null){
                userId=IdUtil.getSnowflakeNextId();
                db.update("insert into sys_user(user_id,tenant_id,dept_id,user_name,nick_name,user_type,password,status,del_flag,external_source,create_by,create_time,update_by,update_time) values(?,?,?,?,?,'sys_user','!external',?,'0',?,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP)",userId,tenant,department,user.username(),user.displayName(),user.enabled()?"0":"1",source.id(),actor,actor);
                db.update("insert into fw_directory_users values(?,?,?,?,?,?,?)",tenant,source.id(),user.externalId(),userId,user.version(),hash,user.enabled());
                added++;
            }else{
                // Never restore a deleted account or overwrite local roles, posts, passwords or a manual disable.
                int count=db.update("update sys_user set user_name=?,nick_name=?,dept_id=?,status=case when ?=false then '1' else status end,update_by=?,update_time=CURRENT_TIMESTAMP where tenant_id=? and user_id=? and external_source=? and del_flag='0'",user.username(),user.displayName(),department,user.enabled(),actor,tenant,userId,source.id());
                if(count!=1)throw failure("SYNC_ACCOUNT_CONFLICT");
                db.update("update fw_directory_users set remote_version=?,content_hash=?,remote_enabled=? where tenant_id=? and source_id=? and external_id=?",user.version(),hash,user.enabled(),tenant,source.id(),user.externalId());
                changed.add(userId);updated++;
            }
        }
        db.update("update fw_directory_sources set checkpoint=?,generation=generation+1 where tenant_id=? and source_id=?",checkpoint,tenant,source.id());
        db.update("update fw_directory_runs set state='SUCCEEDED',completed_at=?,added=?,updated=?,skipped=? where id=?",System.currentTimeMillis(),added,updated,skipped,run);
        return Map.of("runId",run,"state","SUCCEEDED","added",added,"updated",updated,"skipped",skipped);
    }
    public List<Map<String,Object>> runs(String tenant,String source){registry.source(tenant,source);return db.queryForList("select * from fw_directory_runs where tenant_id=? and source_id=? order by created_at desc limit 100",tenant,source);}
    private void lock(String tenant){if(db.queryForList("select tenant_id from sys_tenant where tenant_id=? for update",tenant).isEmpty())throw failure("SYNC_TENANT_MISSING");}
    private static boolean bounded(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max;}
    private static void validate(DirectoryAdapter.User u){if(u==null||!bounded(u.externalId(),128)||!bounded(u.username(),30)||!u.username().equals(u.username().strip())||!bounded(u.displayName(),30)||!bounded(u.departmentId(),128)||u.version()<1)throw failure("SYNC_INVALID_USER");}
    static String hash(DirectoryAdapter.User u){try{String value=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(u);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw failure("SYNC_INVALID_USER");}}
    private static SyncFailure failure(String code){return new SyncFailure(code);}
    private static class SyncFailure extends RuntimeException {final String code;SyncFailure(String code){this.code=code;}}
}
