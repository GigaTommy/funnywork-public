package com.funnywork;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import java.time.Clock;
import java.util.*;

@Service
public class ControlService {
    private final JdbcTemplate db;
    private final Json json;
    private final AuthService auth;
    private final Clock clock;
    private final long leaseMs,reserveMs,freshMs;
    private final int maxAttempts;
    private final TenantLocks locks;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher publisher;
    @org.springframework.beans.factory.annotation.Autowired private ExecutionResources executionResources;
    @org.springframework.beans.factory.annotation.Autowired private ManagementTasks managementTasks;
    @org.springframework.beans.factory.annotation.Autowired private TaskAttribution taskAttribution;
    private final ArtifactService artifacts;
    private final ToolRegistry toolRegistry;
    public ControlService(JdbcTemplate db,Json json,AuthService auth,Clock clock,
            TenantLocks locks,PlatformTransactionManager transactions,ApplicationEventPublisher publisher,ArtifactService artifacts,ToolRegistry toolRegistry,
            @Value("${funnywork.lease-ms}") long leaseMs,@Value("${funnywork.reserve-ms}") long reserveMs,
            @Value("${funnywork.node-fresh-ms}") long freshMs,@Value("${funnywork.max-attempts}") int maxAttempts) {
        this.db=db;this.json=json;this.auth=auth;this.clock=clock;
        this.locks=locks;this.transactions=new TransactionTemplate(transactions);this.publisher=publisher;
        this.artifacts=artifacts;this.toolRegistry=toolRegistry;
        this.leaseMs=leaseMs;this.reserveMs=reserveMs;this.freshMs=freshMs;this.maxAttempts=maxAttempts;
    }
    // Independent tenants do not contend on one global scheduling mutex.
    // Operations within a tenant remain short and serialized; no network/model call holds this lock.
    private void guard(Identity actor) { locks.lock(actor.tenant()); }
    @Transactional
    public Map<String,Object> setSchedulingPolicy(Identity actor,int maxConcurrency) {
        guard(actor);actor=auth.current(actor);actor.requireUser("ADMIN");
        if(maxConcurrency<1 || maxConcurrency>10000)throw new ApiException(400,"INVALID_QUOTA","Concurrency must be between 1 and 10000");
        db.update("update tenant_guards set max_concurrency=? where tenant_id=?",maxConcurrency,actor.tenant());
        auth.audit(actor,"SCHEDULING_POLICY_CHANGED",Map.of("maxConcurrency",maxConcurrency));
        return Map.of("maxConcurrency",maxConcurrency);
    }
    private Identity agentPolicyActor(Identity actor,String agent,String zone) {
        actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);
        one("select id from agent_versions where id=? and tenant_id=?",agent,actor.tenant());
        return actor;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> agentSchedulingPolicy(Identity actor,String agent,String zone) {
        actor=agentPolicyActor(actor,agent,zone);
        int tenantLimit=db.queryForObject("select max_concurrency from tenant_guards where tenant_id=?",Integer.class,actor.tenant());
        var rows=db.queryForList("select max_concurrency from agent_scheduling_limits where tenant_id=? and agent_id=? and zone=?",actor.tenant(),agent,zone);
        int limit=rows.isEmpty()?tenantLimit:((Number)rows.get(0).get("max_concurrency")).intValue();
        long occupied=db.queryForObject("select count(*) from attempts a join work_units u on u.id=a.unit_id join tasks t on t.id=u.task_id where t.tenant_id=? and t.agent_id=? and t.zone=? and a.state in ('RESERVED','RUNNING') and a.expires_at>?",Long.class,actor.tenant(),agent,zone,clock.millis());
        Map<String,Object> result=new LinkedHashMap<>();result.put("agentId",agent);result.put("zone",zone);result.put("configured",!rows.isEmpty());
        result.put("maxConcurrency",rows.isEmpty()?null:limit);result.put("effectiveLimit",Math.min(tenantLimit,limit));result.put("occupied",occupied);result.put("observedAt",clock.millis());
        return result;
    }
    @Transactional
    public void setAgentSchedulingPolicy(Identity actor,String agent,String zone,Integer limit) {
        guard(actor);actor=agentPolicyActor(actor,agent,zone);
        if(limit!=null && (limit<1 || limit>10000))throw new ApiException(400,"INVALID_QUOTA","Concurrency must be between 1 and 10000");
        db.update("delete from agent_scheduling_limits where tenant_id=? and agent_id=? and zone=?",actor.tenant(),agent,zone);
        if(limit!=null)db.update("insert into agent_scheduling_limits(tenant_id,agent_id,zone,max_concurrency) values(?,?,?,?)",actor.tenant(),agent,zone,limit);
        auth.audit(actor,"AGENT_SCHEDULING_POLICY_CHANGED",Map.of("agentId",agent,"zone",zone,"maxConcurrency",limit==null?"INHERIT":limit));
        publisher.publishEvent(new TenantWorkChanged(actor.tenant()));
    }
    private record AgentZone(String agent,String zone) {}
    @Transactional
    public Map<String,Object> setAdmissionPolicy(Identity actor,int limit) {
        guard(actor);actor=auth.current(actor);actor.requireUser("ADMIN");
        if(limit<1 || limit>1000000)throw new ApiException(400,"INVALID_QUOTA","Outstanding unit limit must be between 1 and 1000000");
        db.update("update tenant_guards set max_outstanding_units=? where tenant_id=?",limit,actor.tenant());
        auth.audit(actor,"ADMISSION_POLICY_CHANGED",Map.of("maxOutstandingUnits",limit));
        return Map.of("maxOutstandingUnits",limit);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> schedulingMetrics(Identity actor) {
        actor=auth.current(actor);actor.requireUser("ADMIN");
        List<Map<String,Object>> zones=new ArrayList<>();
        long now=clock.millis();
        for(String zone:new TreeSet<>(actor.zones())) {
            var units=db.queryForList("select u.state,count(*) as amount from work_units u join tasks t on t.id=u.task_id where t.tenant_id=? and t.zone=? group by u.state",actor.tenant(),zone);
            Long oldest=db.queryForObject("select min(t.created_at) from work_units u join tasks t on t.id=u.task_id where t.tenant_id=? and t.zone=? and t.state='RUNNING' and u.state='READY'",Long.class,actor.tenant(),zone);
            var nodes=db.queryForMap("select count(*) as ready_nodes,coalesce(sum(n.capacity),0) as ready_slots from nodes n join principals p on p.id=n.id where n.tenant_id=? and n.zone=? and n.status='READY' and n.admin_paused=false and n.last_seen>? and p.enabled=true",actor.tenant(),zone,now-freshMs);
            var delivery=db.queryForList("select d.state,count(*) as amount from node_dispatch d join nodes n on n.id=d.node_id where d.tenant_id=? and n.zone=? group by d.state",actor.tenant(),zone);
            zones.add(Map.of("zone",zone,"units",units,"nodes",nodes,"messages",delivery,"oldestReadyTaskAgeMs",oldest==null?0:Math.max(0,now-oldest)));
        }
        int quota=db.queryForObject("select max_concurrency from tenant_guards where tenant_id=?",Integer.class,actor.tenant());
        return Map.of("observedAt",now,"maxConcurrency",quota,"zones",zones,"consistentSnapshot",false);
    }
    private static String s(Map<String,Object> r,String k) { return (String)r.get(k); }
    private static long n(Map<String,Object> r,String k) { return ((Number)r.get(k)).longValue(); }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
    }
    private void event(String tenant,String task,String actor,String type,Object payload) {
        db.update("insert into events(tenant_id,task_id,actor_id,event_type,payload_json,created_at) values(?,?,?,?,?,?)",tenant,task,actor,type,json.write(payload),clock.millis());
        publisher.publishEvent(new TenantWorkChanged(tenant));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> listNodes(Identity actor,String after,int limit) {
        actor=auth.current(actor);actor.requireUser("ADMIN");
        if(after==null || after.length()>64 || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid node page");
        long now=clock.millis();
        if(actor.zones().isEmpty())return Map.of("items",List.of(),"observedAt",now);
        List<Object> params=new ArrayList<>(List.of(actor.tenant(),after));params.addAll(actor.zones());params.add(limit+1);
        var rows=db.queryForList("select n.id,n.zone,n.capacity,n.capability,n.status,n.last_seen,n.admission_reason,n.admin_paused,p.display_name,p.enabled from nodes n join principals p on p.id=n.id and p.tenant_id=n.tenant_id where n.tenant_id=? and n.id>? and n.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+") order by n.id limit ?",params.toArray());
        boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));
        Map<String,Long> occupied=new HashMap<>();
        if(!rows.isEmpty()) {
            List<Object> countArgs=new ArrayList<>(List.of(actor.tenant(),now));for(var row:rows)countArgs.add(row.get("id"));
            var counts=db.queryForList("select node_id,count(*) as amount from attempts where tenant_id=? and expires_at>? and state in ('RESERVED','RUNNING') and node_id in ("+String.join(",",Collections.nCopies(rows.size(),"?"))+") group by node_id",countArgs.toArray());
            for(var count:counts)occupied.put(s(count,"node_id"),n(count,"amount"));
        }
        for(var row:rows) {
            boolean enabled=Boolean.TRUE.equals(row.get("enabled"));
            boolean fresh=n(row,"last_seen")>now-freshMs;
            long busy=occupied.getOrDefault(s(row,"id"),0L);
            String availability=!enabled?"DISABLED":!fresh?"OFFLINE":Boolean.TRUE.equals(row.get("admin_paused"))?"ADMIN_PAUSED":!"READY".equals(row.get("status"))?"DRAINING":"READY";
            row.put("availability",availability);row.put("occupiedSlots",busy);
            row.put("availableSlots","READY".equals(availability)?Math.max(0,n(row,"capacity")-busy):0L);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("items",rows);result.put("observedAt",now);result.put("consistentSnapshot",false);
        if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    @Transactional
    public void nodeAdmission(Identity actor,String id,boolean paused) {
        guard(actor);actor=auth.current(actor);actor.requireUser("ADMIN");
        var row=one("select * from nodes where id=? and tenant_id=?",id,actor.tenant());
        actor.requireZone(s(row,"zone"));
        db.update("update nodes set admin_paused=? where id=? and tenant_id=?",paused,id,actor.tenant());
        if(paused)for(var attempt:db.queryForList("select * from attempts where node_id=? and tenant_id=? and state='RESERVED'",id,actor.tenant())) {
            // No execution started: maintenance must not consume the task's retry budget.
            if(!currentGeneration(attempt))continue;
            db.update("update work_units set attempts=attempts-1 where id=? and epoch=? and state='RESERVED' and attempts>0",s(attempt,"unit_id"),n(attempt,"epoch"));
            expire(attempt,"YIELDED");
        }
        auth.audit(actor,paused?"NODE_ADMISSION_PAUSED":"NODE_ADMISSION_RESUMED",Map.of("nodeId",id));
        publisher.publishEvent(new TenantWorkChanged(actor.tenant()));
    }
    @Transactional
    public Map<String,Object> registerNode(Identity actor,Requests.Node req) {
        guard(actor);
        actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(req.zone());
        String id=AuthService.id(),token=auth.newToken();
        auth.create(id,actor.tenant(),"NODE",req.name(),Set.of(),Set.of(req.zone()),token);
        db.update("insert into nodes(id,tenant_id,zone,capacity,capability,status,last_seen) values(?,?,?,?,?,'DRAINING',?)",id,actor.tenant(),req.zone(),req.capacity(),req.capability(),clock.millis());
        auth.audit(actor,"NODE_REGISTERED",Map.of("nodeId",id,"zone",req.zone(),"capacity",req.capacity()));
        return Map.of("nodeId",id,"token",token,"expiresInSeconds",86400,"capability",req.capability());
    }
    private static String sourceMatch(String node,String task) {
        return "("+node+".capability<>'model.read.v1' or not exists(select 1 from task_document_bindings b join task_tool_grants g on g.task_id=b.task_id and g.tool_version_id=b.tool_version_id join tool_versions v on v.id=b.tool_version_id where b.task_id="+task+".id and b.enabled=true and v.enabled=true and not exists(select 1 from node_source_deployments d where d.tenant_id="+task+".tenant_id and d.node_id="+node+".id and d.principal_id="+task+".owner_id and d.source_id=b.source_id and d.deployment_hash=b.deployment_hash)))";
    }
    private static String maintenanceMatch(String node,String task) {
        return " and not exists(select 1 from maintenance_desktop_bindings sb join maintenance_sync_check_targets sc on sc.device_id=sb.device_id and sc.tenant_id=sb.tenant_id where sb.node_id="+node+".id and sb.tenant_id="+task+".tenant_id and (sc.state='CLAIMED' or (sc.state='UNKNOWN' and sc.stopped_evidence_digest is null))) and (not exists(select 1 from agent_versions av where av.id="+task+".agent_id and av.capability='repository.maintain.v1') or exists(select 1 from maintenance_node_profiles p join maintenance_specs ms on ms.platform=p.platform join work_units mu on mu.id=ms.unit_id and mu.task_id="+task+".id join maintenance_problems mp on mp.id=ms.problem_id join maintenance_repositories mr on mr.id=mp.repository_id where p.git_version<>'unavailable' and p.node_id="+node+".id and p.tenant_id="+task+".tenant_id and mr.enabled=true and (not exists(select 1 from maintenance_delivery_jobs dj where dj.task_id="+task+".id) or p.executor_version='funnywork-native-v2')))";
    }
    private boolean sourceMatches(String node,String task) {
        return db.queryForObject("select count(*) from nodes n join tasks t on t.id=? and t.tenant_id=n.tenant_id where n.id=? and "+sourceMatch("n","t"),Integer.class,task,node)==1;
    }
    void invalidateSourceReservations(String tenant) {
        for(var attempt:db.queryForList("select a.* from attempts a join nodes n on n.id=a.node_id join work_units u on u.id=a.unit_id join tasks t on t.id=u.task_id where a.tenant_id=? and a.state='RESERVED' and not ("+sourceMatch("n","t")+")",tenant)) {
            if(!currentGeneration(attempt))continue;
            db.update("update work_units set attempts=attempts-1 where id=? and epoch=? and state='RESERVED' and attempts>0",s(attempt,"unit_id"),n(attempt,"epoch"));
            expire(attempt,"YIELDED");
        }
    }
    @Transactional
    public Map<String,Object> reportSourceDeployments(Identity node,Requests.NodeSources body) {
        guard(node);node=auth.current(node);node.requireNode();
        if(!"model.read.v1".equals(db.queryForObject("select capability from nodes where id=? and tenant_id=?",String.class,node.id(),node.tenant())))throw ApiException.forbidden();
        if(body==null || body.sources()==null || body.sources().size()>64)throw new ApiException(400,"INVALID_SOURCE_MANIFEST","Invalid source manifest");
        var identities=new HashSet<String>();
        for(var source:body.sources()) {
            if(source==null || source.executionPrincipalId()==null || !source.executionPrincipalId().matches("[A-Za-z0-9-]{1,64}") || source.sourceId()==null || !source.sourceId().matches("[A-Za-z0-9._-]{1,120}") || source.deploymentHash()==null || !source.deploymentHash().matches("[a-f0-9]{64}") || !identities.add(source.executionPrincipalId()+":"+source.sourceId()))throw new ApiException(400,"INVALID_SOURCE_MANIFEST","Invalid or duplicate source identity");
            if(db.queryForObject("select count(*) from principals where tenant_id=? and id=? and kind='USER'",Integer.class,node.tenant(),source.executionPrincipalId())!=1)throw ApiException.forbidden();
        }
        var expected=body.sources().stream().map(source->Map.<String,Object>of("executionPrincipalId",source.executionPrincipalId(),"sourceId",source.sourceId(),"deploymentHash",source.deploymentHash())).sorted(Comparator.comparing(row->row.get("executionPrincipalId")+":"+row.get("sourceId"))).toList();
        var old=sourceDeployments(node);
        if(old.get("sources").equals(expected))return Map.of("updated",false,"count",expected.size());
        db.update("delete from node_source_deployments where tenant_id=? and node_id=?",node.tenant(),node.id());
        for(var source:body.sources())db.update("insert into node_source_deployments values(?,?,?,?,?)",node.tenant(),node.id(),source.executionPrincipalId(),source.sourceId(),source.deploymentHash());
        invalidateSourceReservations(node.tenant());
        publisher.publishEvent(new TenantWorkChanged(node.tenant()));
        auth.audit(node,"NODE_SOURCE_MANIFEST_UPDATED",Map.of("count",expected.size()));
        return Map.of("updated",true,"count",expected.size());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> sourceDeployments(Identity node) {
        node=auth.current(node);node.requireNode();
        var rows=db.queryForList("select principal_id,source_id,deployment_hash from node_source_deployments where tenant_id=? and node_id=? order by principal_id,source_id",node.tenant(),node.id());
        return Map.of("nodeId",node.id(),"sources",rows.stream().map(row->Map.<String,Object>of("executionPrincipalId",row.get("principal_id"),"sourceId",row.get("source_id"),"deploymentHash",row.get("deployment_hash"))).toList());
    }
    @Transactional
    public void heartbeat(Identity node,boolean accepting) {heartbeat(node,accepting,null);}
    @Transactional
    public void heartbeat(Identity node,boolean accepting,Requests.AdmissionReason reason) {
        if(reason==null)reason=accepting?Requests.AdmissionReason.READY:Requests.AdmissionReason.NOT_ACCEPTING;
        if(accepting!=(reason==Requests.AdmissionReason.READY))throw new ApiException(400,"INVALID_ADMISSION_REASON","Reason must match accepting state");
        guard(node);node=auth.current(node);node.requireNode();
        db.update("update nodes set status=?,last_seen=?,admission_reason=? where id=? and tenant_id=?",accepting?"READY":"DRAINING",clock.millis(),reason.name(),node.id(),node.tenant());
        if(!accepting) {
            for(var a:db.queryForList("select * from attempts where node_id=? and state='RESERVED'",node.id())) {
                expire(a,"YIELDED");
            }
        }
        publisher.publishEvent(new TenantWorkChanged(node.tenant()));
    }
    @Transactional
    public Map<String,Object> publishAgent(Identity actor,Requests.Agent req) {
        guard(actor);
        actor=auth.current(actor);actor.requireUser("ADMIN");
        String id=AuthService.id();
        db.update("insert into agent_versions(id,tenant_id,name,version,capability,status,created_by) values(?,?,?,?,?,'PUBLISHED',?)",id,actor.tenant(),req.name(),req.version(),req.capability(),actor.id());
        auth.audit(actor,"AGENT_PUBLISHED",Map.of("agentId",id,"capability",req.capability()));
        return Map.of("agentId",id,"name",req.name(),"version",req.version(),"capability",req.capability());
    }
    @Transactional
    public Map<String,Object> createTask(Identity actor,String requestKey,Requests.Task req) {
        return createTask(actor,requestKey,req,false,"");
    }
    @Transactional
    Map<String,Object> createMaintenanceTask(Identity actor,String requestKey,Requests.Task req) {
        return createTask(actor,requestKey,req,true,"");
    }
    @Transactional
    Map<String,Object> createScopedTask(Identity actor,String requestKey,Requests.Task req){return createTask(actor,requestKey,req,true,"");}
    @Transactional
    Map<String,Object> createScopedTask(Identity actor,String requestKey,Requests.Task req,String project){return createTask(actor,requestKey,req,true,project);}
    private Map<String,Object> createTask(Identity actor,String requestKey,Requests.Task req,boolean scoped,String project) {
        guard(actor);actor=auth.current(actor);actor.requireUser("OPERATOR");actor.requireZone(req.zone());
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9._:-]{1,120}")) throw new ApiException(400,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key is required (1–120 safe characters)");
        var dependencies=TaskDependencies.validate(req.inputs().size(),req.dependencies());
        if(req.attachments().size()>20 || new HashSet<>(req.attachments()).size()!=req.attachments().size())throw new ApiException(400,"INVALID_ATTACHMENTS","At most 20 distinct attachment versions allowed");
        // Preserve legacy request hashes when the optional attachment list is empty.
        var canonical=new LinkedHashMap<String,Object>();canonical.put("agentId",req.agentId());canonical.put("zone",req.zone());canonical.put("inputs",req.inputs());
        if(!req.attachments().isEmpty())canonical.put("attachments",req.attachments());
        if(!dependencies.isEmpty())canonical.put("dependencies",TaskDependencies.canonical(dependencies));
        if(req.dispatchPaused())canonical.put("dispatchPaused",true);
        if(req.title()!=null)canonical.put("title",req.title());
        String hash=AuthService.hash(json.write(canonical));
        var old=db.queryForList("select * from tasks where tenant_id=? and owner_id=? and request_key=?",actor.tenant(),actor.id(),requestKey);
        if(!old.isEmpty()) {
            if(!s(old.get(0),"request_hash").equals(hash)) throw ApiException.conflict("IDEMPOTENCY_CONFLICT");
            return Map.of("taskId",s(old.get(0),"id"),"reused",true);
        }
        var agent=one("select * from agent_versions where id=? and tenant_id=? and status='PUBLISHED'",req.agentId(),actor.tenant());
        if(Capabilities.REPOSITORY.equals(s(agent,"capability")) && !scoped)throw ApiException.conflict("MAINTENANCE_SPEC_REQUIRED");
        if(Capabilities.ANNUAL.equals(s(agent,"capability")) && !scoped)throw ApiException.conflict("REPORT_SPEC_REQUIRED");
        TaskDependencies.capability(s(agent,"capability"),dependencies);
        int backlog=db.queryForObject("select count(*) from work_units u join tasks t on t.id=u.task_id where t.tenant_id=? and u.state in ('READY','RESERVED','RUNNING','WAITING_DEPENDENCY')",Integer.class,actor.tenant());
        int limit=db.queryForObject("select max_outstanding_units from tenant_guards where tenant_id=?",Integer.class,actor.tenant());
        if((long)backlog+req.inputs().size()>limit)throw new ApiException(429,"QUEUE_CAPACITY_EXCEEDED","Outstanding work limit exceeded; retry later with the same idempotency key");
        String id=AuthService.id();
        db.update("insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at,dispatch_paused,title) values(?,?,?,?,?,?,'RUNNING',?,?,?,?,?)",id,actor.tenant(),actor.id(),actor.authVersion(),req.zone(),req.agentId(),requestKey,hash,clock.millis(),req.dispatchPaused(),req.title());
        taskAttribution.capture(actor,id,req.zone(),project);
        db.update("insert into task_plans(task_id,tenant_id,version,input_count,request_hash,created_at) values(?,?,1,?,?,?)",id,actor.tenant(),req.inputs().size(),hash,clock.millis());
        var waiting=new HashSet<Integer>();for(var edge:dependencies)waiting.add(edge.successor());
        for(int i=0;i<req.inputs().size();i++) {
            db.update("insert into work_units(id,task_id,ordinal,input_text,state,epoch,attempts,tenant_id) values(?,?,?,?,?,0,0,?)",AuthService.id(),id,i,req.inputs().get(i),waiting.contains(i)?"WAITING_DEPENDENCY":"READY",actor.tenant());
        }
        for(var edge:dependencies)db.update("insert into task_dependencies(task_id,tenant_id,predecessor,successor,completion) values(?,?,?,?,?)",id,actor.tenant(),edge.predecessor(),edge.successor(),edge.completion().name());
        for(var attachment:req.attachments())artifacts.attach(actor,id,attachment);
        toolRegistry.applyDefaults(actor,id,req.agentId(),req.zone());
        db.update("insert into task_skills(task_id,tenant_id,skill_id) select ?,d.tenant_id,d.skill_id from agent_skill_defaults d join published_skills s on s.id=d.skill_id where d.tenant_id=? and d.agent_id=? and s.zone=? and s.enabled=true",id,actor.tenant(),req.agentId(),req.zone());
        event(actor.tenant(),id,actor.id(),"TASK_CREATED",Map.of("units",req.inputs().size()));
        if(req.dispatchPaused())event(actor.tenant(),id,actor.id(),"TASK_DISPATCH_PAUSED",Map.of("atCreation",true));
        return Map.of("taskId",id,"reused",false);
    }
    @Transactional
    public Map<String,Object> appendPlan(Identity actor,String taskId,Requests.PlanAppend req) {
        guard(actor);actor=auth.current(actor);actor.requireUser("OPERATOR");
        var task=visibleTask(actor,taskId);
        if(!allowed(task))throw ApiException.forbidden();
        if(req.expectedVersion()==null || req.expectedVersion()<1 || req.expectedVersion()>99 || req.inputs()==null
                || req.inputs().isEmpty() || req.inputs().size()>99 || req.inputs().stream().anyMatch(x->x==null || x.length()>10000))
            throw new ApiException(400,"INVALID_PLAN","Invalid plan append");
        // Validate and normalize the bounded edge list before using it in an idempotency digest.
        var added=TaskDependencies.validate(100,req.dependencies());
        var body=new LinkedHashMap<String,Object>();body.put("expectedVersion",req.expectedVersion());body.put("inputs",req.inputs());body.put("dependencies",TaskDependencies.canonical(added));
        String hash=AuthService.hash(json.write(body));int next=req.expectedVersion()+1;
        var existing=db.queryForList("select request_hash from task_plans where task_id=? and version=?",taskId,next);
        if(!existing.isEmpty()) {
            if(!hash.equals(s(existing.get(0),"request_hash")))throw ApiException.conflict("PLAN_VERSION_CONFLICT");
            return Map.of("taskId",taskId,"planVersion",next,"reused",true);
        }
        if(n(task,"plan_version")!=req.expectedVersion())throw ApiException.conflict("PLAN_VERSION_CONFLICT");
        if(!"RUNNING".equals(s(task,"state")))throw ApiException.conflict("TASK_NOT_RUNNING");
        String capability=db.queryForObject("select capability from agent_versions where id=?",String.class,task.get("agent_id"));
        if(!Set.of(TextStats.CAPABILITY,"model.read.v1").contains(capability))throw new ApiException(400,"UNSUPPORTED_DEPENDENCY_CAPABILITY","Unsupported plan capability");
        TaskDependencies.capability(capability,added);
        int previous=db.queryForObject("select count(*) from work_units where task_id=?",Integer.class,taskId);
        int total=previous+req.inputs().size();
        var all=new ArrayList<Requests.Dependency>();
        for(var row:db.queryForList("select predecessor,successor,completion from task_dependencies where task_id=?",taskId))
            all.add(new Requests.Dependency(((Number)row.get("predecessor")).intValue(),((Number)row.get("successor")).intValue(),Requests.DependencyCompletion.valueOf((String)row.get("completion"))));
        for(var edge:added) {
            if(edge.successor()<previous)throw ApiException.conflict("EXISTING_PLAN_IMMUTABLE");
            all.add(edge);
        }
        TaskDependencies.validate(total,all);
        int backlog=db.queryForObject("select count(*) from work_units where tenant_id=? and state in ('READY','RESERVED','RUNNING','WAITING_DEPENDENCY')",Integer.class,actor.tenant());
        int limit=db.queryForObject("select max_outstanding_units from tenant_guards where tenant_id=?",Integer.class,actor.tenant());
        if((long)backlog+req.inputs().size()>limit)throw new ApiException(429,"QUEUE_CAPACITY_EXCEEDED","Outstanding work limit exceeded");
        db.update("insert into task_plans(task_id,tenant_id,version,input_count,request_hash,created_at) values(?,?,?,?,?,?)",taskId,actor.tenant(),next,total,hash,clock.millis());
        var waiting=new HashSet<Integer>();for(var edge:added)waiting.add(edge.successor());
        for(int i=0;i<req.inputs().size();i++) {
            int ordinal=previous+i;
            db.update("insert into work_units(id,task_id,ordinal,input_text,state,epoch,attempts,tenant_id,plan_version) values(?,?,?,?,?,0,0,?,?)",
                AuthService.id(),taskId,ordinal,req.inputs().get(i),waiting.contains(ordinal)?"WAITING_DEPENDENCY":"READY",actor.tenant(),next);
        }
        for(var edge:added)db.update("insert into task_dependencies(task_id,tenant_id,predecessor,successor,completion) values(?,?,?,?,?)",taskId,actor.tenant(),edge.predecessor(),edge.successor(),edge.completion().name());
        db.update("update tasks set plan_version=? where id=?",next,taskId);
        refreshTask(taskId);
        event(actor.tenant(),taskId,actor.id(),"TASK_PLAN_APPENDED",Map.of("planVersion",next,"addedUnits",req.inputs().size()));
        return Map.of("taskId",taskId,"planVersion",next,"reused",false);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> plans(Identity actor,String taskId) {
        visibleTask(actor,taskId);
        return db.queryForList("select version,input_count,created_at from task_plans where task_id=? order by version",taskId);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> planSnapshot(Identity actor,String taskId,int version,int after,int limit) {
        var task=visibleTask(actor,taskId);
        if(version<1 || version>100 || after< -1 || after>99 || limit<1 || limit>5)
            throw new ApiException(400,"INVALID_PLAN_PAGE","Invalid plan version or page boundary");
        var plan=one("select input_count,created_at from task_plans where task_id=? and version=?",taskId,version);
        int total=((Number)plan.get("input_count")).intValue();
        var rows=db.queryForList("select id,ordinal,input_text,plan_version from work_units where task_id=? and plan_version<=? and ordinal>? and ordinal<? order by ordinal limit ?",taskId,version,after,total,limit+1);
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));
        // Bound by five 10,000-character inputs even with JSON escaping; no current execution state is mixed in.
        var items=new ArrayList<Map<String,Object>>();
        for(var row:page)items.add(Map.of("unitId",row.get("id"),"ordinal",row.get("ordinal"),"input",row.get("input_text"),
            "inputSha256",AuthService.hash(s(row,"input_text")),"createdInVersion",row.get("plan_version")));
        List<Map<String,Object>> edges=page.isEmpty()?List.of():db.queryForList("select predecessor,successor,completion from task_dependencies where task_id=? and successor>? and successor<=? and predecessor<? order by successor,predecessor",taskId,after,n(page.get(page.size()-1),"ordinal"),total);
        var agent=one("select name,version,capability from agent_versions where id=?",task.get("agent_id"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("taskId",taskId);result.put("planVersion",version);result.put("createdAt",plan.get("created_at"));
        result.put("agent",Map.of("id",task.get("agent_id"),"name",agent.get("name"),"version",agent.get("version"),"capability",agent.get("capability")));
        result.put("zone",task.get("zone"));result.put("totalSteps",total);result.put("items",items);result.put("dependencies",edges);
        result.put("scope","INPUTS_AND_DEPENDENCIES");result.put("hasMore",more);
        if(more)result.put("nextAfter",page.get(page.size()-1).get("ordinal"));
        return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> listTasks(Identity actor,String after,int limit) {
        return listTasks(actor,after,limit,"","","",0,0);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> listTasks(Identity actor,String after,int limit,String state,String zone,String query,long from,long until) {
        actor=auth.current(actor);
        if(!"USER".equals(actor.kind()) || Collections.disjoint(actor.roles(),Set.of("OPERATOR","VIEWER")))throw ApiException.forbidden();
        if(limit<1 || limit>100 || after.length()>64)throw new ApiException(400,"INVALID_PAGE","Invalid page boundary");
        if(query.length()>160 || zone.length()>64 || from<0 || until<0 || (until>0 && from>=until) ||
            (!state.isEmpty() && !Set.of("RUNNING","SUCCEEDED","CANCELLED","FAILED","BLOCKED_AUTH","RECONCILING","RECONCILED","OUTPUT_READY","PARTIAL_OUTPUT","PARTIALLY_SUCCEEDED").contains(state)))
            throw new ApiException(400,"INVALID_FILTER","Invalid task filter");
        if(!zone.isEmpty())actor.requireZone(zone);
        if(actor.zones().isEmpty())return Map.of("items",List.of());
        List<Object> parameters=new ArrayList<>(List.of(actor.tenant(),actor.id(),after));parameters.addAll(actor.zones());
        String filter="";
        if(!state.isEmpty()){filter+=" and t.state=?";parameters.add(state);}
        if(!zone.isEmpty()){filter+=" and t.zone=?";parameters.add(zone);}
        if(from>0){filter+=" and t.created_at>=?";parameters.add(from);}
        if(until>0){filter+=" and t.created_at<?";parameters.add(until);}
        if(!query.isBlank()){
            String pattern="%"+query.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            filter+=" and (lower(coalesce(t.title,v.name)) like ? escape '!' or lower(v.name) like ? escape '!' or t.id=?)";
            parameters.add(pattern);parameters.add(pattern);parameters.add(query.strip());
        }
        parameters.add(limit+1);
        var rows=db.queryForList("select t.id,coalesce(t.title,v.name) as title,t.state,t.zone,t.dispatch_paused,t.created_at,p.display_name as owner_name,v.name as agent_name,v.capability from tasks t join agent_versions v on v.id=t.agent_id join principals p on p.id=t.owner_id and p.tenant_id=t.tenant_id where t.tenant_id=? and t.owner_id=? and t.id>? and t.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+")"+filter+" order by t.id limit ?",parameters.toArray());
        boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));
        Map<String,Object> page=new LinkedHashMap<>();page.put("items",rows);if(more)page.put("nextCursor",rows.get(rows.size()-1).get("id"));return page;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> taskSummary(Identity actor) {
        actor=auth.current(actor);
        if(!"USER".equals(actor.kind()) || Collections.disjoint(actor.roles(),Set.of("OPERATOR","VIEWER")))throw ApiException.forbidden();
        if(actor.zones().isEmpty())return Map.of("items",List.of(),"total",0,"asOf",clock.millis());
        List<Object> parameters=new ArrayList<>(List.of(actor.tenant(),actor.id()));parameters.addAll(actor.zones());
        var rows=db.queryForList("select state,count(*) as count from tasks where tenant_id=? and owner_id=? and zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+") group by state order by state",parameters.toArray());
        long total=rows.stream().mapToLong(row->((Number)row.get("count")).longValue()).sum();
        return Map.of("items",rows,"total",total,"asOf",clock.millis());
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> availableAgents(Identity actor) {
        actor=auth.current(actor);
        if(!"USER".equals(actor.kind()) || Collections.disjoint(actor.roles(),Set.of("ADMIN","OPERATOR","VIEWER")))throw ApiException.forbidden();
        return db.queryForList("select id,name,version,capability from agent_versions where tenant_id=? and status='PUBLISHED' order by name,version,id limit 100",actor.tenant());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> agentPage(Identity actor,String after,int limit,String capability) {
        return agentPage(actor,after,limit,capability,"");
    }
    @Transactional(readOnly=true)
    public Map<String,Object> agentPage(Identity actor,String after,int limit,String capability,String query) {
        actor=auth.current(actor);
        if(!"USER".equals(actor.kind()) || Collections.disjoint(actor.roles(),Set.of("ADMIN","OPERATOR","VIEWER")))throw ApiException.forbidden();
        if(query.length()>100 || limit<1 || limit>100 || !after.matches("[A-Za-z0-9-]{0,64}") || (!capability.isEmpty() && !Capabilities.SUPPORTED.contains(capability)))throw new ApiException(400,"INVALID_PAGE","Invalid capability page");
        List<Object> parameters=new ArrayList<>(List.of(actor.tenant(),after));
        String filter="";
        if(!capability.isEmpty()){filter=" and capability=?";parameters.add(capability);}
        if(!query.isBlank()){
            filter+=" and (lower(name) like ? escape '!' or id=?)";
            parameters.add("%"+query.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");parameters.add(query.strip());
        }
        parameters.add(limit+1);
        var rows=db.queryForList("select id,name,version,capability from agent_versions where tenant_id=? and id>? and status='PUBLISHED'"+filter+" order by id limit ?",parameters.toArray());
        boolean more=rows.size()>limit;
        if(more)rows=new ArrayList<>(rows.subList(0,limit));
        Map<String,Object> page=new LinkedHashMap<>();page.put("items",rows);
        if(more)page.put("nextCursor",rows.get(rows.size()-1).get("id"));
        return page;
    }
    private Map<String,Object> visibleTask(Identity actor,String taskId) {
        actor=auth.current(actor);
        if(!actor.kind().equals("USER") || !(actor.roles().contains("VIEWER")||actor.roles().contains("OPERATOR"))) throw ApiException.forbidden();
        // Administrators have no implicit access to another user's business data.
        var task=one("select * from tasks where id=? and tenant_id=? and owner_id=?",taskId,actor.tenant(),actor.id());
        actor.requireZone(s(task,"zone"));return task;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> getTask(Identity actor,String taskId) {
        var task=visibleTask(actor,taskId);
        var units=db.queryForList("select u.id,u.ordinal,u.state,u.epoch,u.attempts,u.plan_version,coalesce(d.result_json,b.result_json,r.result_json,c.result_json) as result_json from work_units u left join unit_results r on r.unit_id=u.id left join unit_reconciliations c on c.unit_id=u.id left join browser_reconciliations b on b.unit_id=u.id left join computer_reconciliations d on d.unit_id=u.id where u.task_id=? order by u.ordinal",taskId);
        for(var unit:units) { Object raw=unit.remove("result_json");unit.put("result",raw==null?null:json.read((String)raw)); }
        var result=new LinkedHashMap<String,Object>(Map.of("taskId",taskId,"capability",db.queryForObject("select capability from agent_versions where id=?",String.class,task.get("agent_id")),"planVersion",n(task,"plan_version"),"state",s(task,"state"),"zone",s(task,"zone"),"dispatchPaused",Boolean.TRUE.equals(task.get("dispatch_paused")),
            "pauseStatus",!"RUNNING".equals(s(task,"state"))?"TERMINAL":!Boolean.TRUE.equals(task.get("dispatch_paused"))?"NOT_REQUESTED":units.stream().anyMatch(u->"RUNNING".equals(u.get("state")))?"DRAINING":"PAUSED",
            "units",units,"dependencies",db.queryForList("select predecessor,successor,completion from task_dependencies where task_id=? order by predecessor,successor",taskId)));
        result.put("title",task.get("title")!=null?task.get("title"):db.queryForObject("select name from agent_versions where id=?",String.class,task.get("agent_id")));
        result.put("createdAt",task.get("created_at"));
        result.put("ownerName",db.queryForObject("select display_name from principals where id=? and tenant_id=?",String.class,task.get("owner_id"),task.get("tenant_id")));
        return result;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> events(Identity actor,String taskId,long after) {
        visibleTask(actor,taskId);
        var result=db.queryForList("select id,event_type,payload_json,created_at from events where task_id=? and tenant_id=? and id>? order by id limit 200",taskId,actor.tenant(),after);
        for(var row:result) row.put("payload",json.read((String)row.remove("payload_json")));
        return result;
    }
    @Transactional
    public Map<String,Object> taskAdmission(Identity actor,String taskId,boolean paused) {
        guard(actor);actor=auth.current(actor);actor.requireUser("OPERATOR");var task=visibleTask(actor,taskId);
        return admissionChecked(actor,task,taskId,paused);
    }
    @Transactional
    public Map<String,Object> managementAdmission(Identity actor,String taskId,boolean paused){guard(actor);actor=auth.current(actor);actor.requireUser("ADMIN");managementTasks.visible(actor,taskId);return admissionChecked(actor,one("select * from tasks where id=? and tenant_id=?",taskId,actor.tenant()),taskId,paused);}
    private Map<String,Object> admissionChecked(Identity actor,Map<String,Object> task,String taskId,boolean paused){
        if(!allowed(task))throw ApiException.forbidden();
        if(!"RUNNING".equals(s(task,"state")))throw ApiException.conflict("TASK_NOT_RUNNING");
        boolean changed=Boolean.TRUE.equals(task.get("dispatch_paused"))!=paused;
        db.update("update tasks set dispatch_paused=? where id=?",paused,taskId);
        if(paused)for(var attempt:db.queryForList("select a.* from attempts a join work_units u on u.id=a.unit_id where u.task_id=? and a.state='RESERVED'",taskId)) {
            if(!currentGeneration(attempt))continue;
            db.update("update work_units set attempts=attempts-1 where id=? and epoch=? and state='RESERVED' and attempts>0",s(attempt,"unit_id"),n(attempt,"epoch"));
            expire(attempt,"YIELDED");
        }
        if(changed)event(actor.tenant(),taskId,actor.id(),paused?"TASK_DISPATCH_PAUSED":"TASK_DISPATCH_RESUMED",Map.of());
        if(!paused)db.update("update work_units set next_dispatch_at=0 where task_id=? and state='READY'",taskId);
        publisher.publishEvent(new TenantWorkChanged(actor.tenant()));
        return Map.of("taskId",taskId,"dispatchPaused",paused);
    }
    @Transactional
    public void cancel(Identity actor,String taskId) {
        guard(actor);var task=visibleTask(actor,taskId);cancelChecked(actor,task,taskId);
    }
    @Transactional
    public void managementCancel(Identity actor,String taskId){guard(actor);actor=auth.current(actor);actor.requireUser("ADMIN");managementTasks.visible(actor,taskId);cancelChecked(actor,one("select * from tasks where id=? and tenant_id=?",taskId,actor.tenant()),taskId);}
    private void cancelChecked(Identity actor,Map<String,Object> task,String taskId){
        if(Set.of("SUCCEEDED","PARTIALLY_SUCCEEDED","OUTPUT_READY","PARTIAL_OUTPUT","FAILED","CANCELLED","RECONCILED").contains(s(task,"state"))) return;
        // Completed outputs remain available; external writes require a separate reconciling state.
        db.update("update tasks set state=? where id=?",hasUnresolvedOperation(taskId)?"RECONCILING":"CANCELLED",taskId);
        executionResources.stop(taskId);
        db.update("update attempts set state='CANCELLED' where state in ('RESERVED','RUNNING') and unit_id in (select id from work_units where task_id=?)",taskId);
        cancelMessages(taskId);
        db.update("update work_units set state='CANCELLED' where task_id=? and state not in ('SUCCEEDED','GENERATED') and not exists(select 1 from unit_reconciliations c where c.unit_id=work_units.id) and not exists(select 1 from browser_reconciliations b where b.unit_id=work_units.id) and not exists(select 1 from computer_reconciliations d where d.unit_id=work_units.id)",taskId);
        db.update("update maintenance_problems set state='BLOCKED' where task_id=? and state='IN_PROGRESS'",taskId);
        event(actor.tenant(),taskId,actor.id(),"TASK_CANCELLED",Map.of());
    }
    private boolean allowed(Map<String,Object> task) {
        var owners=db.queryForList("select * from principals where id=? and tenant_id=? and enabled=true",s(task,"owner_id"),s(task,"tenant_id"));
        if(owners.isEmpty())return false;
        var p=owners.get(0);
        // Source snapshots survive context edits/deletion. Recheck before dispatch, lease use and output.
        if(db.queryForObject("select count(*) from employee_context_sources s where s.task_id=? and (not exists(select 1 from artifacts a join artifact_versions v on v.artifact_id=a.id and v.version=s.artifact_version where a.id=s.artifact_id and a.tenant_id=? and a.zone=? and (a.owner_id=? or exists(select 1 from artifact_grants g where g.artifact_id=a.id and g.principal_id=?))) or not exists(select 1 from tasks t where t.id=s.source_task_id and t.tenant_id=? and t.owner_id=? and t.zone=?))",Integer.class,s(task,"id"),s(task,"tenant_id"),s(task,"zone"),s(task,"owner_id"),s(task,"owner_id"),s(task,"tenant_id"),s(task,"owner_id"),s(task,"zone"))>0)return false;
        return n(p,"auth_version")==n(task,"owner_auth_version") && json.strings(s(p,"roles")).contains("OPERATOR") && json.strings(s(p,"zones")).contains(s(task,"zone"))
            && db.queryForObject("select count(*) from agent_versions where id=? and tenant_id=? and status='PUBLISHED'",Integer.class,s(task,"agent_id"),s(task,"tenant_id"))==1;
    }
    private void cancelMessages(String taskId) {
        db.update("update node_dispatch set state='CANCELLED' where state<>'ACKED' and attempt_id in (select a.id from attempts a join work_units u on u.id=a.unit_id where u.task_id=?)",taskId);
    }
    private void block(Map<String,Object> task) {
        String id=s(task,"id");
        executionResources.stop(id);
        db.update("update tasks set state='BLOCKED_AUTH' where id=?",id);
        db.update("update attempts set state='REVOKED' where state in ('RESERVED','RUNNING') and unit_id in (select id from work_units where task_id=?)",id);
        cancelMessages(id);
        db.update("update work_units set state='BLOCKED_AUTH' where task_id=? and state in ('READY','RESERVED','RUNNING','WAITING_DEPENDENCY')",id);
        db.update("update maintenance_problems set state='BLOCKED' where task_id=? and state='IN_PROGRESS'",id);
        event(s(task,"tenant_id"),id,null,"TASK_AUTH_REVOKED",Map.of());
    }
    boolean hasUnresolvedOperation(String taskId) {
        if(db.queryForObject("select count(*) from computer_actions a join work_units u on u.id=a.unit_id where u.task_id=? and not exists(select 1 from computer_reconciliations r where r.unit_id=u.id)",Integer.class,taskId)>0)return true;
        if(db.queryForObject("select count(*) from browser_actions a join work_units u on u.id=a.unit_id where u.task_id=? and not exists(select 1 from browser_reconciliations r where r.unit_id=u.id)",Integer.class,taskId)>0)return true;
        return db.queryForObject("select count(*) from external_operations o where o.task_id=? and (o.state in ('DISPATCHED','UNKNOWN') or (o.state in ('CONFIRMED','REJECTED') and exists(select 1 from unit_operations b where b.operation_id=o.id and not exists(select 1 from unit_results r where r.unit_id=b.unit_id) and not exists(select 1 from unit_reconciliations c where c.unit_id=b.unit_id))))",Integer.class,taskId)>0;
    }
    void refreshTask(String id) {
        String state=db.queryForObject("select state from tasks where id=?",String.class,id);
        executionResources.settle(id);
        if(!"RUNNING".equals(state))return;
        TaskDependencies.advance(db,id);
        int total=db.queryForObject("select count(*) from work_units where task_id=?",Integer.class,id);
        int success=db.queryForObject("select count(*) from work_units where task_id=? and state='SUCCEEDED'",Integer.class,id);
        int active=db.queryForObject("select count(*) from work_units where task_id=? and state in ('READY','RESERVED','RUNNING','WAITING_DEPENDENCY')",Integer.class,id);
        int generated=db.queryForObject("select count(*) from work_units where task_id=? and state='GENERATED'",Integer.class,id);
        if(total==success) db.update("update tasks set state='SUCCEEDED' where id=?",id);
        else if(active==0 && hasUnresolvedOperation(id))db.update("update tasks set state='RECONCILING' where id=?",id);
        else if(active==0) db.update("update tasks set state=? where id=?",generated>0?(generated+success==total?"OUTPUT_READY":"PARTIAL_OUTPUT"):(success>0?"PARTIALLY_SUCCEEDED":"FAILED"),id);
    }
    private void expire(Map<String,Object> attempt,String reason) {
        if(!currentGeneration(attempt))return; // Never turn a restored old attempt into an automatic retry.
        var unit=one("select * from work_units where id=?",s(attempt,"unit_id"));
        var task=one("select * from tasks where id=?",s(unit,"task_id"));
        executionResources.expired(s(attempt,"id"),"RUNNING".equals(attempt.get("state")));
        db.update("update attempts set state=? where id=?",reason,s(attempt,"id"));
        db.update("update node_dispatch set state='CANCELLED' where attempt_id=?",s(attempt,"id"));
        if(n(unit,"epoch")==n(attempt,"epoch") && Set.of("RESERVED","RUNNING").contains(s(unit,"state"))) {
            boolean unknownMaintenance="RUNNING".equals(attempt.get("state")) && db.queryForObject("select count(*) from maintenance_specs where unit_id=?",Integer.class,unit.get("id"))==1;
            db.update("update work_units set state=? where id=?",unknownMaintenance || n(unit,"attempts")>=maxAttempts?"FAILED":"READY",s(unit,"id"));
            if(unknownMaintenance)db.update("update maintenance_problems set state='BLOCKED' where task_id=? and state='IN_PROGRESS'",task.get("id"));
        }
        event(s(task,"tenant_id"),s(task,"id"),s(attempt,"node_id"),"ATTEMPT_"+reason,Map.of("attemptId",s(attempt,"id")));
        refreshTask(s(task,"id"));
    }
    public int tick() {
        int count=0;
        for(String tenant:db.queryForList("select tenant_id from tenant_guards order by tenant_id",String.class))count+=tickTenant(tenant);
        return count;
    }
    public int tickTenant(String tenant) {
        return transactions.execute(status->locks.tryLock(tenant)?dispatchTenant(tenant):0);
    }
    private int dispatchTenant(String tenant) {
        long now=clock.millis();
        // A restored old attempt requires reconciliation; expiry is not evidence of physical stop.
        if(db.queryForObject("select count(*) from attempts where tenant_id=? and state in ('RESERVED','RUNNING') and recovery_generation<>(select generation from recovery_generation where id=1)",Integer.class,tenant)>0)return 0;
        invalidateSourceReservations(tenant);
        for(var a:db.queryForList("select a.*,u.task_id from attempts a join work_units u on u.id=a.unit_id where a.tenant_id=? and a.state in ('RESERVED','RUNNING')",tenant)) {
            try { executionResources.require(tenant,s(a,"task_id"),s(a,"node_id"),s(a,"id")); }
            catch(ApiException revoked) { expire(a,"EXPIRED"); }
        }
        for(var a:db.queryForList("select a.* from attempts a join nodes n on n.id=a.node_id where n.tenant_id=? and a.state in ('RESERVED','RUNNING') and a.expires_at<=?",tenant,now))expire(a,"EXPIRED");
        // Revocation is also evaluated for running tasks, not just when they next become ready.
        for(var t:db.queryForList("select * from tasks where tenant_id=? and state='RUNNING'",tenant))if(!allowed(t))block(t);
        int limit=db.queryForObject("select max_concurrency from tenant_guards where tenant_id=?",Integer.class,tenant);
        int active=db.queryForObject("select count(*) from attempts a join nodes n on n.id=a.node_id where n.tenant_id=? and a.state in ('RESERVED','RUNNING')",Integer.class,tenant);
        Map<AgentZone,Long> agentLimits=new HashMap<>(),agentOccupied=new HashMap<>();
        for(var row:db.queryForList("select agent_id,zone,max_concurrency from agent_scheduling_limits where tenant_id=?",tenant))agentLimits.put(new AgentZone(s(row,"agent_id"),s(row,"zone")),n(row,"max_concurrency"));
        if(!agentLimits.isEmpty())for(var row:db.queryForList("select t.agent_id,t.zone,count(*) as occupied from attempts a join work_units u on u.id=a.unit_id join tasks t on t.id=u.task_id join agent_scheduling_limits q on q.tenant_id=t.tenant_id and q.agent_id=t.agent_id and q.zone=t.zone where t.tenant_id=? and a.state in ('RESERVED','RUNNING') group by t.agent_id,t.zone",tenant))agentOccupied.put(new AgentZone(s(row,"agent_id"),s(row,"zone")),n(row,"occupied"));
        Map<String,PriorityQueue<Map<String,Object>>> candidatesByZone=new HashMap<>();
        Map<String,Map<String,Object>> tasks=new HashMap<>();
        Map<String,Long> busyByNode=new HashMap<>();
        int assigned=0;
        for(var u:db.queryForList("select u.* from work_units u join tasks t on u.task_id=t.id where t.tenant_id=? and u.state='READY' and u.next_dispatch_at<=? and t.state='RUNNING' and t.dispatch_paused=false and exists(select 1 from agent_versions av where av.id=t.agent_id and (av.capability in ('text.stats.v1','model.read.v1') or (av.capability='report.annual.v1' and exists(select 1 from annual_report_jobs j join annual_datasets d on d.id=j.dataset_id where j.unit_id=u.id and d.enabled=true)) or (av.capability='repository.maintain.v1' and exists(select 1 from maintenance_specs ms join maintenance_problems mp on mp.id=ms.problem_id join maintenance_repositories mr on mr.id=mp.repository_id where ms.unit_id=u.id and mr.enabled=true)) or (av.capability='connector.write.v1' and exists(select 1 from unit_operations b where b.unit_id=u.id)) or (av.capability='browser.write.v1' and exists(select 1 from browser_grants bg where bg.unit_id=u.id and bg.enabled=true)))) order by u.next_dispatch_at,t.created_at,t.id,u.ordinal limit 64",tenant,now)) {
            if(active+assigned>=limit)break;
            var t=tasks.computeIfAbsent(s(u,"task_id"),key->one("select t.*,v.capability from tasks t join agent_versions v on v.id=t.agent_id where t.id=?",key));
            // A nonmatching head of queue must not starve later work that can run elsewhere.
            db.update("update work_units set next_dispatch_at=? where id=?",now+1000,s(u,"id"));
            AgentZone scope=new AgentZone(s(t,"agent_id"),s(t,"zone"));
            if(!executionResources.writerAvailable(s(t,"id")))continue;
            if(agentLimits.containsKey(scope) && agentOccupied.getOrDefault(scope,0L)>=agentLimits.get(scope))continue;
            var candidates=candidatesByZone.computeIfAbsent(s(t,"zone")+":"+s(t,"capability")+(Set.of("model.read.v1",Capabilities.REPOSITORY,Capabilities.ANNUAL).contains(s(t,"capability"))?":"+s(t,"id"):""),partition->{
                var queue=new PriorityQueue<Map<String,Object>>(Comparator.<Map<String,Object>>comparingLong(v->n(v,"busy")).thenComparing(v->s(v,"id")));
                // Aggregate occupancy per candidate pool; model pools are task-specific for source matching.
                var rows=db.queryForList("select n.*,coalesce(occupancy.busy,0) as busy from nodes n join tasks t on t.id=? join principals p on p.id=n.id left join (select node_id,count(*) as busy from attempts where state in ('RESERVED','RUNNING') group by node_id) occupancy on occupancy.node_id=n.id where n.tenant_id=? and n.zone=? and n.status='READY' and n.admin_paused=false and n.last_seen>? and (n.capability=? or exists(select 1 from node_capabilities nc join agent_versions cv on cv.capability=nc.capability where nc.node_id=n.id and cv.id=t.agent_id)) and p.enabled=true and "+sourceMatch("n","t")+maintenanceMatch("n","t"),s(t,"id"),tenant,s(t,"zone"),now-freshMs,s(t,"capability"));
                for(var row:rows) {if(!executionResources.eligible(tenant,s(t,"id"),s(row,"id")))continue;busyByNode.merge(s(row,"id"),n(row,"busy"),Math::max);if(n(row,"busy")<n(row,"capacity"))queue.add(row);}
                return queue;
            });
            // Multiple source-specific pools may contain the same node. Refresh stale heap
            // entries against monotonic round occupancy before reserving any additional slot.
            while(!candidates.isEmpty()) {
                var head=candidates.peek();long busy=busyByNode.get(s(head,"id"));
                if(n(head,"busy")==busy && busy<n(head,"capacity"))break;
                candidates.remove();head.put("busy",busy);if(busy<n(head,"capacity"))candidates.add(head);
            }
            if(!candidates.isEmpty()) {
                var node=candidates.remove();
                String attemptId=AuthService.id();long epoch=n(u,"epoch")+1;
                db.update("update work_units set state='RESERVED',epoch=?,attempts=attempts+1 where id=?",epoch,s(u,"id"));
                db.update("insert into attempts(id,unit_id,node_id,epoch,state,expires_at,created_at,tenant_id,recovery_generation) values(?,?,?,?,'RESERVED',?,?,?,(select generation from recovery_generation where id=1))",attemptId,s(u,"id"),s(node,"id"),epoch,now+reserveMs,now,tenant);
                executionResources.reserve(tenant,s(t,"id"),s(node,"id"),attemptId,epoch);
                db.update("insert into node_dispatch(id,tenant_id,node_id,attempt_id,state,available_at,created_at) values(?,?,?,?,'PENDING',?,?)",AuthService.id(),tenant,s(node,"id"),attemptId,now,now);
                event(s(t,"tenant_id"),s(t,"id"),s(node,"id"),"UNIT_RESERVED",Map.of("attemptId",attemptId,"epoch",epoch));
                if(agentLimits.containsKey(scope))agentOccupied.merge(scope,1L,Long::sum);
                assigned++;node.put("busy",n(node,"busy")+1);busyByNode.put(s(node,"id"),n(node,"busy"));
                if(n(node,"busy")<n(node,"capacity"))candidates.add(node);
            }
        }
        return assigned;
    }
    @Transactional
    public Map<String,Object> poll(Identity node) {
        guard(node);node=auth.current(node);node.requireNode();
        if(!locallyReady(node))return Map.of("status","NO_WORK");
        // Running assignments are redelivered after a lost HTTP response; workers deduplicate by attemptId.
        for(var a:db.queryForList("select * from attempts where node_id=? and state in ('RUNNING','RESERVED') and expires_at>? order by created_at,id",node.id(),clock.millis())) {
            if(!currentGeneration(a))continue;
            var u=one("select * from work_units where id=?",s(a,"unit_id"));
            var t=one("select * from tasks where id=?",s(u,"task_id"));
            if(!allowed(t)) { block(t);continue; }
            if(!"RUNNING".equals(s(t,"state")))continue;
            if("RESERVED".equals(s(a,"state")) && !ready(node))continue;
            var result=startOrResume(node,a,u,t);
            for(var message:db.queryForList("select * from node_dispatch where attempt_id=?",s(a,"id")))recordAck(node,s(message,"id"));
            return result;
        }
        return Map.of("status","NO_WORK");
    }
    private boolean locallyReady(Identity node) {
        var row=one("select * from nodes where id=? and tenant_id=?",node.id(),node.tenant());
        return s(row,"status").equals("READY") && n(row,"last_seen")>clock.millis()-freshMs;
    }
    private boolean ready(Identity node) {
        var row=one("select * from nodes where id=? and tenant_id=?",node.id(),node.tenant());
        return !Boolean.TRUE.equals(row.get("admin_paused")) && s(row,"status").equals("READY") && n(row,"last_seen")>clock.millis()-freshMs;
    }
    private Map<String,Object> startOrResume(Identity node,Map<String,Object> a,Map<String,Object> u,Map<String,Object> t) {
        requireCurrentGeneration(a);
        executionResources.require(s(t,"tenant_id"),s(t,"id"),node.id(),s(a,"id"));
        if(!allowed(t))throw ApiException.forbidden();
        if(!"RUNNING".equals(s(t,"state")) || !Set.of("RESERVED","RUNNING").contains(s(a,"state")) || n(a,"expires_at")<=clock.millis() || n(u,"epoch")!=n(a,"epoch"))throw ApiException.conflict("STALE_LEASE");
        if("RESERVED".equals(s(a,"state")) && !sourceMatches(node.id(),s(t,"id")))throw ApiException.conflict("SOURCE_DEPLOYMENT_MISMATCH");
        long expires=n(a,"expires_at");
        if("RESERVED".equals(s(a,"state")) && Boolean.TRUE.equals(t.get("dispatch_paused")))throw ApiException.conflict("TASK_DISPATCH_PAUSED");
        if("RESERVED".equals(s(a,"state"))) {
            expires=clock.millis()+leaseMs;
            db.update("update attempts set state='RUNNING',expires_at=? where id=?",expires,s(a,"id"));
            db.update("update work_units set state='RUNNING' where id=?",s(u,"id"));
            event(node.tenant(),s(t,"id"),node.id(),"UNIT_STARTED",Map.of("attemptId",s(a,"id")));
        }
        var work=new LinkedHashMap<String,Object>(Map.of("status","WORK","dependencyCount",db.queryForObject("select count(*) from task_dependencies where task_id=? and successor=?",Integer.class,s(t,"id"),n(u,"ordinal")),"planVersion",n(u,"plan_version"),"attemptId",s(a,"id"),"unitId",s(u,"id"),"epoch",n(a,"epoch"),"capability",db.queryForObject("select capability from agent_versions where id=?",String.class,t.get("agent_id")),"input",s(u,"input_text"),"expiresAt",expires,"leaseDurationMs",Math.max(0,expires-clock.millis())));
        work.put("recoveryGeneration",s(a,"recovery_generation"));work.put("taskId",s(t,"id"));return work;
    }
    private void recordAck(Identity node,String messageId) {
        if(db.queryForObject("select count(*) from node_inbox where message_id=?",Integer.class,messageId)==0)
            db.update("insert into node_inbox(message_id,node_id,acknowledged_at) values(?,?,?)",messageId,node.id(),clock.millis());
        db.update("update node_dispatch set state='ACKED' where id=?",messageId);
    }
    @Transactional
    public List<Map<String,Object>> messages(Identity node) {return messages(node,16);}
    @Transactional
    public List<Map<String,Object>> messages(Identity node,int credit) {
        guard(node);node=auth.current(node);node.requireNode();
        if(credit<0 || credit>16)throw new ApiException(400,"INVALID_CREDIT","Credit must be between 0 and 16");
        if(!locallyReady(node) || credit==0)return List.of();
        int outstanding=db.queryForObject("select count(*) from node_dispatch d join attempts a on a.id=d.attempt_id where d.node_id=? and d.state='DELIVERED' and d.available_at>? and a.state in ('RESERVED','RUNNING') and a.expires_at>?",Integer.class,node.id(),clock.millis(),clock.millis());
        int budget=Math.max(0,credit-outstanding);
        if(budget==0)return List.of();
        List<Map<String,Object>> result=new ArrayList<>();
        var messages=db.queryForList("select d.* from node_dispatch d join attempts a on a.id=d.attempt_id where d.node_id=? and d.tenant_id=? and d.state in ('PENDING','DELIVERED') and d.available_at<=? and a.state in ('RESERVED','RUNNING') and a.expires_at>? order by d.created_at,d.id limit ?",node.id(),node.tenant(),clock.millis(),clock.millis(),budget);
        for(var message:messages) {
            var a=one("select * from attempts where id=?",s(message,"attempt_id"));
            if(!currentGeneration(a))continue;
            var u=one("select * from work_units where id=?",s(a,"unit_id"));
            var t=one("select * from tasks where id=?",s(u,"task_id"));
            if(!allowed(t)) {block(t);continue;}
            if(!"RUNNING".equals(s(t,"state")))continue;
            if("RESERVED".equals(s(a,"state")) && !ready(node))continue;
            db.update("update node_dispatch set state='DELIVERED',deliveries=deliveries+1,available_at=? where id=?",clock.millis()+5000,s(message,"id"));
            // No task text or credential is included until the node acknowledges under fresh authorization.
            result.add(Map.of("messageId",s(message,"id"),"type","DISPATCH","attemptId",s(a,"id"),"epoch",n(a,"epoch"),"deliveryCount",n(message,"deliveries")+1));
        }
        return result;
    }
    @Transactional
    public Map<String,Object> acknowledge(Identity node,String messageId) {
        guard(node);node=auth.current(node);node.requireNode();
        var message=one("select * from node_dispatch where id=? and node_id=? and tenant_id=?",messageId,node.id(),node.tenant());
        var a=one("select * from attempts where id=?",s(message,"attempt_id"));
        requireCurrentGeneration(a);
        var u=one("select * from work_units where id=?",s(a,"unit_id"));
        var t=one("select * from tasks where id=?",s(u,"task_id"));
        if(!allowed(t))throw ApiException.forbidden();
        if("FAILED".equals(s(a,"state")) && a.get("failure_code")!=null)return Map.of("status","COMPLETED","attemptId",s(a,"id"),"outcome","FAILED");
        if("CANCELLED".equals(s(message,"state")))throw ApiException.conflict("STALE_LEASE");
        if("SUCCEEDED".equals(s(a,"state")))return Map.of("status","COMPLETED","attemptId",s(a,"id"));
        if("RUNNING".equals(s(a,"state"))?!locallyReady(node):!ready(node))throw ApiException.conflict("NODE_NOT_READY");
        var work=startOrResume(node,a,u,t);
        recordAck(node,messageId);
        return work;
    }
    private boolean currentGeneration(Map<String,Object> attempt) {
        return Objects.equals(attempt.get("recovery_generation"),db.queryForObject("select generation from recovery_generation where id=1",String.class));
    }
    private void requireCurrentGeneration(Map<String,Object> attempt) {
        if(!currentGeneration(attempt))throw ApiException.conflict("STALE_LEASE");
    }
    private Map<String,Object> ownAttempt(Identity node,String id,long epoch) {
        node=auth.current(node);node.requireNode();
        var a=one("select * from attempts where id=? and node_id=?",id,node.id());
        requireCurrentGeneration(a);
        if(n(a,"epoch")!=epoch)throw ApiException.conflict("STALE_LEASE");
        return a;
    }
    private Map<String,Object> checkExecution(Map<String,Object> a) {
        var u=one("select * from work_units where id=?",s(a,"unit_id"));
        var t=one("select * from tasks where id=?",s(u,"task_id"));
        if(!allowed(t))throw ApiException.forbidden();
        if(!"RUNNING".equals(s(t,"state")) || !"RUNNING".equals(s(a,"state")) || n(a,"expires_at")<=clock.millis() || n(u,"epoch")!=n(a,"epoch"))throw ApiException.conflict("STALE_LEASE");
        return u;
    }
    record ModelLease(Identity owner,String taskId,String unitId) {}
    ModelLease modelLease(Identity node,String id,long epoch,boolean completedReceipt) {
        return executionLease(node,id,epoch,completedReceipt,"model.read.v1");
    }
    ModelLease maintenanceLease(Identity node,String id,long epoch,boolean completedReceipt) {
        var lease=executionLease(node,id,epoch,completedReceipt,Capabilities.REPOSITORY);
        executionResources.require(node.tenant(),lease.taskId(),node.id(),id);
        if(!completedReceipt && db.queryForObject("select count(*) from nodes n join tasks t on t.id=? and t.tenant_id=n.tenant_id where n.id=?"+maintenanceMatch("n","t"),Integer.class,lease.taskId(),node.id())!=1)throw ApiException.conflict("MAINTENANCE_NODE_MISMATCH");
        if(!completedReceipt && db.queryForObject("select count(*) from maintenance_delivery_jobs j join maintenance_problems p on p.id=j.problem_id where j.task_id=? and (j.problem_version<>p.version or p.state not in ('AWAITING_ACCEPTANCE','ACCEPTED') or (j.mode='DELIVER' and (p.state<>'ACCEPTED' or exists(select 1 from maintenance_intakes i where i.problem_id=p.id and i.state='SOURCE_CHANGED'))))",Integer.class,lease.taskId())>0)throw ApiException.conflict("MAINTENANCE_DELIVERY_CHANGED");
        return lease;
    }
    ModelLease annualLease(Identity node,String id,long epoch,boolean completed){
        if(db.queryForObject("select count(*) from node_capabilities where node_id=? and capability=?",Integer.class,node.id(),Capabilities.ANNUAL)!=1)throw ApiException.conflict("CAPABILITY_MISMATCH");
        var lease=executionLease(node,id,epoch,completed,Capabilities.ANNUAL);
        executionResources.require(node.tenant(),lease.taskId(),node.id(),id);return lease;
    }
    ModelLease connectorLease(Identity node,String id,long epoch,boolean completedReceipt) {
        return executionLease(node,id,epoch,completedReceipt,"connector.write.v1");
    }
    ModelLease browserLease(Identity node,String id,long epoch) {
        return browserLease(node,id,epoch,false);
    }
    ModelLease browserLease(Identity node,String id,long epoch,boolean completedReceipt) {
        return executionLease(node,id,epoch,completedReceipt,"browser.write.v1");
    }
    ModelLease computerLease(Identity node,String id,long epoch) {
        var lease=executionLease(node,id,epoch,false,Capabilities.COMPUTER);
        if(!locallyReady(node))throw ApiException.conflict("NODE_NOT_READY");
        return lease;
    }
    ModelLease computerReportLease(Identity node,String id,long epoch,boolean completedReceipt) {
        return executionLease(node,id,epoch,completedReceipt,Capabilities.COMPUTER);
    }
    private ModelLease executionLease(Identity node,String id,long epoch,boolean completedReceipt,String capability) {
        var attempt=ownAttempt(node,id,epoch);
        var unit=one("select * from work_units where id=?",s(attempt,"unit_id"));
        var task=one("select * from tasks where id=?",s(unit,"task_id"));
        if(!allowed(task))throw ApiException.forbidden();
        if(!capability.equals(db.queryForObject("select capability from agent_versions where id=?",String.class,task.get("agent_id"))))throw ApiException.conflict("CAPABILITY_MISMATCH");
        if(!(completedReceipt && ("SUCCEEDED".equals(attempt.get("state")) || ("FAILED".equals(attempt.get("state")) && attempt.get("failure_code")!=null))))checkExecution(attempt);
        var owner=one("select * from principals where id=?",task.get("owner_id"));
        Identity delegated=new Identity(s(owner,"id"),node.tenant(),s(owner,"kind"),json.strings(s(owner,"roles")),json.strings(s(owner,"zones")),n(owner,"auth_version"));
        return new ModelLease(delegated,s(task,"id"),s(unit,"id"));
    }
    void modelOutputEvent(Identity node,String task,String unit,String artifact){event(node.tenant(),task,node.id(),"MODEL_OUTPUT_STORED",Map.of("unitId",unit,"artifactId",artifact,"reviewState","UNREVIEWED"));}
    @Transactional
    public Map<String,Object> modelFailure(Identity node,String id,Requests.ModelFailure failure) {
        guard(node);
        var lease=modelLease(node,id,failure.epoch(),true);
        var attempt=ownAttempt(node,id,failure.epoch());
        if(attempt.get("failure_code")!=null) {
            if(!failure.failureCode().name().equals(attempt.get("failure_code")))throw ApiException.conflict("RESULT_CONFLICT");
            return Map.of("accepted",true,"duplicate",true);
        }
        modelLease(node,id,failure.epoch(),false);
        db.update("update attempts set failure_code=? where id=?",failure.failureCode().name(),id);
        expire(attempt,"FAILED");
        long count=db.queryForObject("select attempts from work_units where id=?",Long.class,lease.unitId());
        long retryAt=clock.millis()+Math.min(30000L,1000L*(1L<<Math.min(count,5)));
        db.update("update work_units set next_dispatch_at=? where id=? and state='READY'",retryAt,lease.unitId());
        event(node.tenant(),lease.taskId(),node.id(),"MODEL_FAILED",Map.of("attemptId",id,"failureCode",failure.failureCode().name()));
        return Map.of("accepted",true,"duplicate",false);
    }
    @Transactional
    public Map<String,Object> renew(Identity node,String id,long epoch) {
        guard(node);var a=ownAttempt(node,id,epoch);checkExecution(a);
        if(db.queryForObject("select count(*) from maintenance_specs where unit_id=?",Integer.class,a.get("unit_id"))==1)maintenanceLease(node,id,epoch,false);
        if(db.queryForObject("select count(*) from annual_report_jobs where unit_id=?",Integer.class,a.get("unit_id"))==1)annualLease(node,id,epoch,false);
        long expires=clock.millis()+leaseMs;
        db.update("update attempts set expires_at=? where id=?",expires,id);
        return Map.of("expiresAt",expires);
    }
    @Transactional
    public Map<String,Object> result(Identity node,String id,Requests.Result result) {
        guard(node);var a=ownAttempt(node,id,result.epoch());
        var u=one("select * from work_units where id=?",s(a,"unit_id"));
        var t=one("select * from tasks where id=?",s(u,"task_id"));
        if(!allowed(t))throw ApiException.forbidden();
        if(!TextStats.CAPABILITY.equals(db.queryForObject("select capability from agent_versions where id=?",String.class,t.get("agent_id"))))throw ApiException.conflict("CAPABILITY_MISMATCH");
        var existing=db.queryForList("select * from unit_results where unit_id=?",s(u,"id"));
        if(!existing.isEmpty()) {
            if(!s(existing.get(0),"attempt_id").equals(id))throw ApiException.conflict("STALE_LEASE");
            if(!TextStats.compute(s(u,"input_text")).equals(result.value()))throw ApiException.conflict("RESULT_CONFLICT");
            return Map.of("accepted",true,"duplicate",true);
        }
        checkExecution(a);
        if(!TextStats.compute(s(u,"input_text")).equals(result.value()))throw new ApiException(422,"VERIFICATION_FAILED","Result does not match the independent verifier");
        db.update("insert into unit_results(unit_id,attempt_id,result_json,accepted_at) values(?,?,?,?)",s(u,"id"),id,json.write(result.value()),clock.millis());
        db.update("update attempts set state='SUCCEEDED' where id=?",id);
        db.update("update work_units set state='SUCCEEDED' where id=?",s(u,"id"));
        event(node.tenant(),s(t,"id"),node.id(),"RESULT_ACCEPTED",Map.of("attemptId",id,"unitId",s(u,"id")));
        refreshTask(s(t,"id"));
        // A verified result frees a slot. Wake a bounded batch in the same tenant/zone.
        db.update("update work_units set next_dispatch_at=? where id in (select u.id from work_units u join tasks queued on queued.id=u.task_id where queued.tenant_id=? and queued.zone=? and queued.state='RUNNING' and u.state='READY' and u.next_dispatch_at>? order by queued.created_at,queued.id,u.ordinal limit 64)",clock.millis(),node.tenant(),s(t,"zone"),clock.millis());
        return Map.of("accepted",true,"duplicate",false);
    }
}
