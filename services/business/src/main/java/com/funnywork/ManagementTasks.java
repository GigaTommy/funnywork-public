package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Organization task metadata. Does not delegate to owner APIs or reveal input/result bodies. */
@Service
public class ManagementTasks {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final Clock clock;
    private final Json json;
    private final TenantLocks locks;
    public ManagementTasks(JdbcTemplate db, AuthService auth, Clock clock,Json json,TenantLocks locks) { this.db=db; this.auth=auth; this.clock=clock; this.json=json;this.locks=locks; }
    public record Filter(String query, String state, String zone, String owner, String capability, long from, long until,String department,String project) {
        public Filter(String query,String state,String zone,String owner,String capability,long from,long until){this(query,state,zone,owner,capability,from,until,"","");}
        public Filter { query=query==null?"":query.strip(); state=state==null?"":state; zone=zone==null?"":zone;
            department=department==null?"":department;project=project==null?"":project;
            owner=owner==null?"":owner; capability=capability==null?"":capability; }
    }
    private record Scope(String where, List<Object> args, Identity actor) {}
    private static final Set<String> STATES=Set.of("RUNNING","SUCCEEDED","FAILED","CANCELLED","BLOCKED_AUTH","RECONCILING","RECONCILED","OUTPUT_READY","PARTIAL_OUTPUT","PARTIALLY_SUCCEEDED");
    private Identity actor(Identity actor) { actor=auth.current(actor); actor.requireUser("ADMIN"); return actor; }
    private int policiesVersion(Identity actor){var rows=db.queryForList("select version from management_task_limits where principal_id=? and tenant_id=?",actor.id(),actor.tenant());return rows.isEmpty()?0:((Number)rows.get(0).get("version")).intValue();}
    private void labels(Identity actor,List<Map<String,Object>> rows){
        for(String kind:List.of("department","project")){
            var ids=new TreeSet<String>();for(var row:rows)if(row.get(kind+"_id") instanceof String id&&!id.isEmpty())ids.add(id);
            var names=new HashMap<String,Object>();if(!ids.isEmpty()){
                var args=new ArrayList<Object>();args.add(actor.tenant());args.addAll(ids);
                for(var row:db.queryForList("select id,name from "+(kind.equals("department")?"task_departments":"execution_projects")+" where tenant_id=? and id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray()))names.put((String)row.get("id"),row.get("name"));
            }
            for(var row:rows)row.put(kind+"_name",names.getOrDefault(row.get(kind+"_id"),"归属未确认"));
        }
    }
    Identity unrestricted(Identity actor){actor=actor(actor);var rows=db.queryForList("select * from management_task_limits where principal_id=? and tenant_id=?",actor.id(),actor.tenant());if(!rows.isEmpty()&&(Boolean.TRUE.equals(rows.get(0).get("restrict_departments"))||Boolean.TRUE.equals(rows.get(0).get("restrict_projects"))))throw ApiException.forbidden();return actor;}
    public record Department(String name,String zone){}
    public record Context(String departmentId,String projectId,int expectedVersion){}
    public record Limits(boolean restrictDepartments,Set<String> departments,boolean restrictProjects,Set<String> projects,int expectedVersion){}
    private void resource(Identity actor,String table,String id,String zone){if(id==null)throw new ApiException(400,"INVALID_TASK_CONTEXT","请选择有效的归属");if(id.isEmpty())return;var rows=db.queryForList("select zone from "+table+" where id=? and tenant_id=?",id,actor.tenant());if(rows.isEmpty())throw ApiException.missing();String actual=(String)rows.get(0).get("zone");actor.requireZone(actual);if(zone!=null&&!zone.equals(actual))throw ApiException.forbidden();}
    @Transactional public Object department(Identity actor,Department body){locks.lock(actor.tenant());actor=unrestricted(actor);if(body.name()==null||body.name().isBlank()||body.name().length()>120||body.zone()==null)throw new ApiException(400,"INVALID_DEPARTMENT","请填写部门名称与区域");actor.requireZone(body.zone());var existing=db.queryForList("select id from task_departments where tenant_id=? and zone=? and name=?",actor.tenant(),body.zone(),body.name().strip());if(!existing.isEmpty())return Map.of("id",existing.get(0).get("id"),"reused",true);String id=AuthService.id();db.update("insert into task_departments values(?,?,?,?,?)",id,actor.tenant(),body.zone(),body.name().strip(),clock.millis());auth.audit(actor,"TASK_DEPARTMENT_CREATED",Map.of("id",id));return Map.of("id",id);}
    @Transactional public Object assign(Identity actor,String task,Context body){locks.lock(actor.tenant());actor=unrestricted(actor);var row=visible(actor,task);String zone=(String)row.get("zone");resource(actor,"task_departments",body.departmentId(),zone);resource(actor,"execution_projects",body.projectId(),zone);int version=row.get("context_version")==null?0:((Number)row.get("context_version")).intValue();if(body.expectedVersion()!=version||version==Integer.MAX_VALUE)throw ApiException.conflict("TASK_CONTEXT_CHANGED");
        if(version==0)db.update("insert into task_management_context values(?,?,?,?,'EXPLICIT_ASSIGNMENT',1,?,?)",task,actor.tenant(),body.departmentId(),body.projectId(),clock.millis(),actor.id());
        else db.update("update task_management_context set department_id=?,project_id=?,basis='EXPLICIT_ASSIGNMENT',version=version+1,changed_at=?,changed_by=? where task_id=?",body.departmentId(),body.projectId(),clock.millis(),actor.id(),task);
        auth.audit(actor,"TASK_CONTEXT_ASSIGNED",Map.of("taskId",task,"departmentId",body.departmentId(),"projectId",body.projectId(),"version",version+1));return Map.of("version",version+1);
    }
    @Transactional public Object saveLimits(Identity actor,String principal,Limits body){locks.lock(actor.tenant());actor=unrestricted(actor);var target=db.queryForList("select zones from principals where id=? and tenant_id=? and kind='USER'",principal,actor.tenant());if(target.isEmpty())throw ApiException.missing();if(!actor.zones().containsAll(json.strings((String)target.get(0).get("zones"))))throw ApiException.forbidden();
        if(body.departments()==null||body.projects()==null||body.departments().size()>100||body.projects().size()>100)throw new ApiException(400,"INVALID_TASK_SCOPE","管理范围最多选择 100 项");for(String id:body.departments()){if(id==null||id.isEmpty())throw ApiException.forbidden();resource(actor,"task_departments",id,null);}for(String id:body.projects()){if(id==null||id.isEmpty())throw ApiException.forbidden();resource(actor,"execution_projects",id,null);}int version=policiesVersion(new Identity(principal,actor.tenant(),"USER",Set.of(),Set.of(),0));if(body.expectedVersion()!=version||version==Integer.MAX_VALUE)throw ApiException.conflict("TASK_SCOPE_CHANGED");
        if(version==0)db.update("insert into management_task_limits values(?,?,?,?,?,?,1)",principal,actor.tenant(),body.restrictDepartments(),json.canonical(new TreeSet<>(body.departments())),body.restrictProjects(),json.canonical(new TreeSet<>(body.projects())));
        else db.update("update management_task_limits set restrict_departments=?,department_ids=?,restrict_projects=?,project_ids=?,version=version+1 where principal_id=? and tenant_id=?",body.restrictDepartments(),json.canonical(new TreeSet<>(body.departments())),body.restrictProjects(),json.canonical(new TreeSet<>(body.projects())),principal,actor.tenant());
        auth.audit(actor,"TASK_SCOPE_SAVED",Map.of("principalId",principal,"version",version+1));return Map.of("version",version+1);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object limits(Identity actor,String principal){actor=unrestricted(actor);var target=db.queryForList("select zones from principals where id=? and tenant_id=? and kind='USER'",principal,actor.tenant());if(target.isEmpty())throw ApiException.missing();if(!actor.zones().containsAll(json.strings((String)target.get(0).get("zones"))))throw ApiException.forbidden();var rows=db.queryForList("select * from management_task_limits where principal_id=? and tenant_id=?",principal,actor.tenant());return rows.isEmpty()?Map.of("version",0,"restrictDepartments",false,"departments",List.of(),"restrictProjects",false,"projects",List.of()):Map.of("version",rows.get(0).get("version"),"restrictDepartments",rows.get(0).get("restrict_departments"),"departments",json.strings((String)rows.get(0).get("department_ids")),"restrictProjects",rows.get(0).get("restrict_projects"),"projects",json.strings((String)rows.get(0).get("project_ids")));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object scopeMembers(Identity actor,String query,String after){return contextMembers(actor,query,after,true);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object attributionMembers(Identity actor,String query,String after){return contextMembers(actor,query,after,false);}
    private Object contextMembers(Identity actor,String query,String after,boolean adminsOnly){actor=unrestricted(actor);if(query.length()>120||after.length()>64)throw new ApiException(400,"INVALID_TASK_FILTER","请缩短搜索条件");var raw=db.queryForList("select id,display_name,zones,roles from principals where tenant_id=? and kind='USER' and id>? and lower(display_name) like ? escape '!' order by id limit 101",actor.tenant(),after,"%"+query.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");boolean more=raw.size()>100;if(more)raw=new ArrayList<>(raw.subList(0,100));String cursor=more?(String)raw.get(raw.size()-1).get("id"):"";var items=new ArrayList<Map<String,Object>>();for(var row:raw)if(actor.zones().containsAll(json.strings((String)row.get("zones")))&&(!adminsOnly||json.strings((String)row.get("roles")).contains("ADMIN")))items.add(Map.of("id",row.get("id"),"display_name",row.get("display_name"),"zones",json.strings((String)row.get("zones"))));return Map.of("items",items,"nextCursor",cursor);}
    private Map<String,Object> directory(Identity actor,String zone,String departmentAfter,String projectAfter){
        if(departmentAfter.length()>64||projectAfter.length()>64)throw new ApiException(400,"INVALID_PAGE","请重新读取归属目录");
        var result=new LinkedHashMap<String,Object>();
        for(String kind:List.of("departments","projects")){
            var rows=new ArrayList<Map<String,Object>>();String table=kind.equals("departments")?"task_departments":"execution_projects";String after=kind.equals("departments")?departmentAfter:projectAfter;
            Set<String> zones=zone==null?actor.zones():Set.of(zone);
            if(!after.equals("__END__")&&!zones.isEmpty()){var args=new ArrayList<Object>();args.add(actor.tenant());args.add(after);args.addAll(zones);rows.addAll(db.queryForList("select id,name,zone from "+table+" where tenant_id=? and id>? and zone in ("+String.join(",",Collections.nCopies(zones.size(),"?"))+") order by id limit 101",args.toArray()));}
            boolean more=rows.size()>100;if(more)rows=new ArrayList<>(rows.subList(0,100));result.put(kind,rows);result.put(kind.equals("departments")?"nextDepartment":"nextProject",more?rows.get(rows.size()-1).get("id"):"");
        }result.put("zones",actor.zones().stream().sorted().toList());return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object scopeOptions(Identity actor,String departmentAfter,String projectAfter){return directory(unrestricted(actor),null,departmentAfter,projectAfter);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> options(Identity actor){return options(actor,"","");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> options(Identity actor,String departmentAfter,String projectAfter){
        actor=actor(actor);if(departmentAfter.length()>64||projectAfter.length()>64)throw new ApiException(400,"INVALID_PAGE","请重新读取筛选项");Scope q=scope(actor,new Filter("","","","","",0,0));var result=new LinkedHashMap<String,Object>();
        for(String kind:List.of("department","project")){
            String table=kind.equals("department")?"task_departments":"execution_projects",after=kind.equals("department")?departmentAfter:projectAfter;var rows=new ArrayList<Map<String,Object>>();
            // Walk the small resource directory and stop at the first scoped task per resource.
            // Joining every matching task then DISTINCT makes this selector grow with task history.
            if(!after.equals("__END__")){var args=new ArrayList<Object>();args.add(q.actor().tenant());args.add(after);args.addAll(q.args());rows.addAll(db.queryForList("select r.id,r.name,r.zone from "+table+" r where r.tenant_id=? and r.id>? and exists(select 1"+JOIN+q.where()+" and c."+kind+"_id=r.id and r.tenant_id=t.tenant_id) order by r.id limit 101",args.toArray()));}
            boolean more=rows.size()>100;if(more)rows=new ArrayList<>(rows.subList(0,100));result.put(kind+"s",rows);result.put(kind.equals("department")?"nextDepartment":"nextProject",more?rows.get(rows.size()-1).get("id"):"");
        }result.put("scopeVersion",policiesVersion(actor));return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Object contextOptions(Identity actor,String task,String departmentAfter,String projectAfter){actor=unrestricted(actor);var row=visible(actor,task);return directory(actor,(String)row.get("zone"),departmentAfter,projectAfter);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> placements(Identity actor,String task){return placements(actor,task,0,"");}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Map<String,Object> placements(Identity actor,String task,long before,String beforeId){
        visible(actor,task);if(before<0||beforeId==null||beforeId.length()>64||(before==0)!=beforeId.isEmpty())throw new ApiException(400,"INVALID_PAGE","请重新读取执行终端");
        var arguments=new ArrayList<Object>(List.of(task,actor.tenant()));String boundary="";if(!beforeId.isEmpty()){boundary=" and (a.created_at<? or (a.created_at=? and a.id<?))";arguments.add(before);arguments.add(before);arguments.add(beforeId);}
        var rows=db.queryForList("select a.id as attempt_id,a.state,a.epoch,a.created_at,case when a.epoch=u.epoch and a.state in ('RESERVED','RUNNING') then true else false end as current_attempt,a.node_id,np.display_name as node_name,e.device_id,d.name as device_name,e.action,w.state as writer_state,e.authorization_json from attempts a join work_units u on u.id=a.unit_id join tasks t on t.id=u.task_id left join principals np on np.id=a.node_id and np.tenant_id=t.tenant_id left join execution_placements e on e.attempt_id=a.id and e.tenant_id=t.tenant_id left join sync_devices d on d.id=e.device_id and d.tenant_id=t.tenant_id left join execution_writers w on w.repository_id=e.repository_id and w.tenant_id=t.tenant_id where t.id=? and t.tenant_id=?"+boundary+" order by a.created_at desc,a.id desc limit 101",arguments.toArray());
        boolean more=rows.size()>100;if(more)rows=new ArrayList<>(rows.subList(0,100));
        for(var row:rows){Object raw=row.remove("authorization_json");if(raw instanceof String text&&json.read(text) instanceof Map<?,?> snapshot&&snapshot.get("route") instanceof Map<?,?> route)for(String key:List.of("project_id","pool_id","profile_id","revision","project_revision","pool_revision","profile_revision"))row.put(key,route.get(key));}
        for(String kind:List.of("project","pool","profile")){
            var ids=new TreeSet<String>();for(var row:rows)if(row.get(kind+"_id") instanceof String id&&!id.isEmpty())ids.add(id);
            var names=new HashMap<String,Object>();if(!ids.isEmpty()){var args=new ArrayList<Object>();args.add(actor.tenant());args.addAll(ids);for(var value:db.queryForList("select id,name from execution_"+kind+"s where tenant_id=? and id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",args.toArray()))names.put((String)value.get("id"),value.get("name"));}
            for(var row:rows)row.put(kind+"_name",names.getOrDefault(row.get(kind+"_id"),"名称未记录"));
        }
        return Map.of("items",rows,"hasMore",more,"nextCreatedAt",more?rows.get(rows.size()-1).get("created_at"):0,"nextId",more?rows.get(rows.size()-1).get("attempt_id"):"","scopeVersion",policiesVersion(actor));
    }
    private Scope scope(Identity actor, Filter f) {
        actor=actor(actor);
        if(f.department().length()>64||f.project().length()>64||f.query().length()>160||f.owner().length()>64||f.zone().length()>64||f.capability().length()>80||
            !f.state().isEmpty()&&!STATES.contains(f.state())||f.from()<0||f.until()<0||f.until()>0&&f.from()>=f.until())
            throw new ApiException(400,"INVALID_TASK_FILTER","请检查任务筛选条件");
        if(!f.zone().isEmpty())actor.requireZone(f.zone());
        var args=new ArrayList<Object>(); args.add(actor.tenant());
        String where=" where t.tenant_id=?";
        if(actor.zones().isEmpty())where+=" and 1=0";
        else { where+=" and t.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+")"; args.addAll(actor.zones().stream().sorted().toList()); }
        var policies=db.queryForList("select * from management_task_limits where principal_id=? and tenant_id=?",actor.id(),actor.tenant());
        if(!policies.isEmpty())for(String dimension:List.of("departments","projects"))if(Boolean.TRUE.equals(policies.get(0).get("restrict_"+dimension))){
            Set<String> ids=json.strings((String)policies.get(0).get(dimension.equals("departments")?"department_ids":"project_ids"));
            if(ids.isEmpty())where+=" and 1=0";
            else{where+=" and c."+(dimension.equals("departments")?"department_id":"project_id")+" in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";args.addAll(ids.stream().sorted().toList());}
        }
        if(f.department().equals("__UNCONFIRMED__"))where+=" and coalesce(c.department_id,'')=''";else if(!f.department().isEmpty()){where+=" and c.department_id=?";args.add(f.department());}
        if(f.project().equals("__UNCONFIRMED__"))where+=" and coalesce(c.project_id,'')=''";else if(!f.project().isEmpty()){where+=" and c.project_id=?";args.add(f.project());}
        String[] columns={"t.state","t.zone","t.owner_id","v.capability"};
        String[] values={f.state(),f.zone(),f.owner(),f.capability()};
        for(int i=0;i<columns.length;i++)if(!values[i].isEmpty()){where+=" and "+columns[i]+"=?";args.add(values[i]);}
        if(f.from()>0){where+=" and t.created_at>=?";args.add(f.from());}
        if(f.until()>0){where+=" and t.created_at<?";args.add(f.until());}
        if(!f.query().isEmpty()){
            where+=" and (lower(coalesce(t.title,v.name)) like ? escape '!' or t.id=?)";
            args.add("%"+f.query().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");args.add(f.query());
        }
        return new Scope(where,args,actor);
    }
    private static final String JOIN=" from tasks t join agent_versions v on v.id=t.agent_id and v.tenant_id=t.tenant_id join principals p on p.id=t.owner_id and p.tenant_id=t.tenant_id left join task_management_context c on c.task_id=t.id and c.tenant_id=t.tenant_id";
    private static final String FIELDS="t.id,coalesce(t.title,v.name) as title,t.owner_id,p.display_name as owner_name,t.zone,t.state,t.created_at,t.dispatch_paused,v.capability,v.name as agent_name,c.department_id,c.project_id,c.basis as context_basis,c.version as context_version";
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> members(Identity actor,String query) {
        if(query.length()>120)throw new ApiException(400,"INVALID_TASK_FILTER","请缩短成员搜索条件");
        Scope q=scope(actor,new Filter("","","","","",0,0));
        var args=new ArrayList<>(q.args());args.add("%"+query.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
        var rows=db.queryForList("select distinct p.id,p.display_name"+JOIN+q.where()+" and lower(p.display_name) like ? escape '!' order by p.display_name,p.id limit 51",args.toArray());
        return Map.of("items",rows.size()>50?rows.subList(0,50):rows,"hasMore",rows.size()>50,"scopeVersion",policiesVersion(q.actor()));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(Identity actor, Filter filter, long before, String beforeId, int limit) {
        Scope q=scope(actor,filter);
        if(limit<1||limit>100||before<0||beforeId==null||beforeId.length()>64||(before==0)!=beforeId.isEmpty())
            throw new ApiException(400,"INVALID_PAGE","请重新查询任务列表");
        var summary=db.queryForList("select t.state,count(*) as count"+JOIN+q.where()+" group by t.state order by t.state",q.args().toArray());
        var args=new ArrayList<>(q.args());String cursor="";
        if(!beforeId.isEmpty()){cursor=" and (t.created_at<? or (t.created_at=? and t.id<?))";args.add(before);args.add(before);args.add(beforeId);}
        args.add(limit+1);
        var rows=db.queryForList("select "+FIELDS+JOIN+q.where()+cursor+" order by t.created_at desc,t.id desc limit ?",args.toArray());
        var result=new LinkedHashMap<String,Object>();
        if(rows.size()>limit){rows=new ArrayList<>(rows.subList(0,limit));var last=rows.get(rows.size()-1);result.put("nextCreatedAt",last.get("created_at"));result.put("nextId",last.get("id"));}
        labels(q.actor(),rows);result.put("scopeVersion",policiesVersion(q.actor()));result.put("items",rows);result.put("summary",summary);result.put("total",summary.stream().mapToLong(row->((Number)row.get("count")).longValue()).sum());
        result.put("zones",q.actor().zones().stream().sorted().toList());result.put("observedAt",clock.millis());result.put("coverage","SERVER_TASKS_ONLY");return result;
    }
    Map<String,Object> visible(Identity actor,String id) {
        Scope q=scope(actor,new Filter("","","","","",0,0));
        var args=new ArrayList<>(q.args());args.add(id);
        var rows=db.queryForList("select "+FIELDS+JOIN+q.where()+" and t.id=?",args.toArray());
        if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
    }
    Set<String> visibleIds(Identity actor,Collection<String> ids){if(ids.isEmpty())return Set.of();if(ids.size()>100)throw new ApiException(400,"INVALID_PAGE","请缩小任务范围");Scope q=scope(actor,new Filter("","","","","",0,0));var args=new ArrayList<>(q.args());args.addAll(ids);return new HashSet<>(db.queryForList("select t.id"+JOIN+q.where()+" and t.id in ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",String.class,args.toArray()));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(Identity actor,String id) {
        var result=new LinkedHashMap<>(visible(actor,id));
        labels(actor,List.of(result));
        result.put("placements",placements(actor,id));
        result.put("scopeVersion",policiesVersion(actor));
        result.put("units",db.queryForList("select id,ordinal,state,epoch,attempts from work_units where task_id=? order by ordinal",id));
        result.put("creationAttribution",db.queryForList("select department_id,department_name,department_basis,member_version,project_id,project_name,project_basis,captured_at from task_creation_attribution where task_id=? and tenant_id=?",id,actor.tenant()));
        result.put("allowedActions",List.of("READ_METADATA"));result.put("contentIncluded",false);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> events(Identity actor,String id,long after) {
        visible(actor,id);if(after<0)throw new ApiException(400,"INVALID_PAGE","请重新读取任务事件");
        var rows=db.queryForList("select id,event_type,created_at from events where task_id=? and tenant_id=? and id>? order by id limit 201",id,actor.tenant(),after);
        var result=new LinkedHashMap<String,Object>();if(rows.size()>200){rows=new ArrayList<>(rows.subList(0,200));result.put("nextCursor",rows.get(rows.size()-1).get("id"));}
        result.put("items",rows);return result;
    }
}
