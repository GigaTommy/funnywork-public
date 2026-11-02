package org.dromara.web.directory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.dromara.common.core.exception.ServiceException;
import java.util.*;
import java.util.concurrent.*;

/** Durable queue: writes are atomic, crashed attempts reconcile their recorded run before retrying. */
@Service
public class DirectoryJobs {
    private final JdbcTemplate db;
    private final DirectoryRegistry registry;
    private final DirectorySync sync;
    private final DirectoryAccess access;
    private final DirectoryPreviews previews;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"directory-jobs");t.setDaemon(true);return t;});
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DirectoryJobs.class);
    private boolean warned;
    public DirectoryJobs(JdbcTemplate db,DirectoryRegistry registry,DirectorySync sync,DirectoryAccess access,DirectoryPreviews previews,PlatformTransactionManager manager){this.db=db;this.registry=registry;this.sync=sync;this.access=access;this.previews=previews;this.tx=new TransactionTemplate(manager);}
    @PostConstruct void start(){worker.scheduleWithFixedDelay(()->{try{tick();warned=false;}catch(RuntimeException e){if(!warned){log.warn("Directory worker unavailable; check database availability and directory jobs migration");warned=true;}}},10,10,TimeUnit.SECONDS);}
    @PreDestroy void stop(){worker.shutdownNow();}
    private void lock(String tenant){if(db.queryForList("select tenant_id from sys_tenant where tenant_id=? for update",tenant).isEmpty())throw new ServiceException("组织不存在");}
    public Map<String,Object> submit(String tenant,String source,long actor,boolean full){
        registry.source(tenant,source);access.require(tenant,actor,true);
        return tx.execute(status->{lock(tenant);return enqueue(tenant,source,actor,full,false);});
    }
    public Map<String,Object> submitPreview(String tenant,String source,long actor,boolean full){
        registry.source(tenant,source);access.require(tenant,actor,true);
        return tx.execute(status->{lock(tenant);return enqueue(tenant,source,actor,full,true);});
    }
    private Map<String,Object> enqueue(String tenant,String source,long actor,boolean full,boolean preview){
        var active=db.queryForList("select * from fw_directory_jobs where tenant_id=? and source_id=? and state in ('QUEUED','RUNNING') order by created_at",tenant,source);
        if(!active.isEmpty()){
            var job=active.get(0);
            if(!Objects.equals(job.get("preview_only"),preview)||!Objects.equals(job.get("full_sync"),full))throw new ServiceException("该身份源已有其他模式的同步任务，请等待其结束",409);
            return job;
        }
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        db.update("insert into fw_directory_jobs(id,tenant_id,source_id,actor_id,full_sync,state,created_at,updated_at,due_at,preview_only) values(?,?,?,?,?,'QUEUED',?,?,?,?)",id,tenant,source,actor,full,now,now,now,preview);
        return db.queryForMap("select * from fw_directory_jobs where id=?",id);
    }
    public List<Map<String,Object>> list(String tenant,String source){registry.source(tenant,source);return db.queryForList("select * from fw_directory_jobs where tenant_id=? and source_id=? order by created_at desc limit 100",tenant,source);}
    public Map<String,Object> schedule(String tenant,String source){registry.source(tenant,source);var rows=db.queryForList("select interval_seconds,enabled,next_at from fw_directory_schedules where tenant_id=? and source_id=?",tenant,source);return rows.isEmpty()?Map.of("interval_seconds",3600,"enabled",false,"next_at",0):rows.get(0);}
    public void schedule(String tenant,String source,long actor,int interval,boolean enabled){
        registry.source(tenant,source);access.require(tenant,actor,true);
        if(interval<60||interval>86400)throw new ServiceException("同步间隔应为 60 至 86400 秒");
        tx.executeWithoutResult(status->{lock(tenant);db.update("delete from fw_directory_schedules where tenant_id=? and source_id=?",tenant,source);db.update("insert into fw_directory_schedules values(?,?,?,?,?,?)",tenant,source,actor,interval,enabled,System.currentTimeMillis()+interval*1000L);});
    }
    void tick(){
        long now=System.currentTimeMillis();
        previews.expire();
        for(var job:db.queryForList("select * from fw_directory_jobs where state='RUNNING' and updated_at<?",now-300_000)){
            tx.executeWithoutResult(status->{lock((String)job.get("tenant_id"));
                if(db.queryForObject("select count(*) from fw_directory_jobs where id=? and state='RUNNING' and updated_at<?",Integer.class,job.get("id"),now-300_000)==0)return;
                boolean committed=db.queryForObject("select count(*) from fw_directory_runs where id=? and state in ('SUCCEEDED','PREVIEW')",Integer.class,job.get("run_id"))==1;
                db.update("update fw_directory_runs set state='FAILED',error_code='SYNC_INTERRUPTED',completed_at=? where id=? and state='FETCHING'",now,job.get("run_id"));
                db.update("update fw_directory_jobs set state=?,updated_at=?,due_at=?,error_code=? where id=?",committed?(Boolean.TRUE.equals(job.get("preview_only"))?"PREVIEW":"SUCCEEDED"):((Number)job.get("attempts")).intValue()<3?"QUEUED":"FAILED",now,now,committed?null:"SYNC_INTERRUPTED",job.get("id"));
            });
        }
        for(var schedule:db.queryForList("select * from fw_directory_schedules where enabled=true and next_at<=?",now)){
            String tenant=(String)schedule.get("tenant_id"),source=(String)schedule.get("source_id");long actor=((Number)schedule.get("actor_id")).longValue();
            tx.executeWithoutResult(status->{lock(tenant);
                if(db.update("update fw_directory_schedules set next_at=? where tenant_id=? and source_id=? and enabled=true and next_at<=?",now+((Number)schedule.get("interval_seconds")).longValue()*1000,tenant,source,now)==0)return;
                if(!access.allowed(tenant,actor,true)){db.update("update fw_directory_schedules set enabled=false where tenant_id=? and source_id=?",tenant,source);return;}
                try{registry.source(tenant,source);}catch(RuntimeException removed){db.update("update fw_directory_schedules set enabled=false where tenant_id=? and source_id=?",tenant,source);return;}
                if(db.queryForObject("select count(*) from fw_directory_jobs where tenant_id=? and source_id=? and state in ('QUEUED','RUNNING')",Integer.class,tenant,source)==0)enqueue(tenant,source,actor,false,false);
            });
        }
        var due=db.queryForList("select * from fw_directory_jobs where state='QUEUED' and due_at<=? order by created_at limit 1",now);
        if(due.isEmpty())return;var job=due.get(0);String id=(String)job.get("id"),run=UUID.randomUUID().toString();
        if(db.update("update fw_directory_jobs set state='RUNNING',run_id=?,attempts=attempts+1,updated_at=? where id=? and state='QUEUED'",run,now,id)!=1)return;
        String tenant=(String)job.get("tenant_id");long actor=((Number)job.get("actor_id")).longValue();
        try{
            access.require(tenant,actor,true);
            sync.sync(tenant,(String)job.get("source_id"),actor,(Boolean)job.get("full_sync"),run,Boolean.TRUE.equals(job.get("preview_only")));
            db.update("update fw_directory_jobs set state=?,updated_at=?,error_code=null where id=? and run_id=?",Boolean.TRUE.equals(job.get("preview_only"))?"PREVIEW":"SUCCEEDED",System.currentTimeMillis(),id,run);
        }catch(RuntimeException failure){
            var runs=db.queryForList("select error_code,state from fw_directory_runs where id=?",run);
            if(!runs.isEmpty()&&Set.of("SUCCEEDED","PREVIEW").contains(runs.get(0).get("state"))){db.update("update fw_directory_jobs set state=?,updated_at=? where id=? and run_id=?",runs.get(0).get("state"),System.currentTimeMillis(),id,run);return;}
            String code=runs.isEmpty()?"SYNC_UNAVAILABLE":Objects.toString(runs.get(0).get("error_code"),"SYNC_FAILED");
            int attempt=((Number)job.get("attempts")).intValue()+1;
            boolean retry=attempt<3&&Set.of("SYNC_FAILED","SYNC_INTERRUPTED").contains(code);
            db.update("update fw_directory_jobs set state=?,updated_at=?,due_at=?,error_code=? where id=? and run_id=?",retry?"QUEUED":"FAILED",System.currentTimeMillis(),System.currentTimeMillis()+attempt*60_000L,code,id,run);
        }
    }
}
