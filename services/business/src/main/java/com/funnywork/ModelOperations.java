package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Scoped operational reporting over the inference ledger. Never reads prompt bodies. */
@Service
public class ModelOperations {
    private final JdbcTemplate db;private final AuthService auth;private final Clock clock;
    public ModelOperations(JdbcTemplate db,AuthService auth,Clock clock){this.db=db;this.auth=auth;this.clock=clock;}
    public record Filter(long from,long until,String zone,String owner,String deployment,String state,String purpose,String session,String task) {
        public Filter(long from,long until,String zone,String owner,String deployment,String state,String purpose){this(from,until,zone,owner,deployment,state,purpose,"","");}
        public Filter{zone=zone==null?"":zone;owner=owner==null?"":owner;deployment=deployment==null?"":deployment;state=state==null?"":state;purpose=purpose==null?"":purpose;session=session==null?"":session;task=task==null?"":task;}
    }
    private record Query(String where,List<Object> args,Identity actor) {}
    private static final Set<String> STATES=Set.of("WAITING","GRANTED","RUNNING","STOPPING","UNKNOWN","SUCCEEDED","FAILED","CANCELLED","EXPIRED");
    private Query query(Identity actor,Filter filter){
        actor=auth.current(actor);actor.requireUser("ADMIN");
        if(filter.from()<0||filter.until()<=filter.from()||filter.until()-filter.from()>93L*86400000)throw invalid();
        for(String value:List.of(filter.zone(),filter.owner(),filter.deployment(),filter.state(),filter.purpose(),filter.session(),filter.task()))if(value.length()>128)throw invalid();
        if(!filter.state().isEmpty()&&!STATES.contains(filter.state()))throw invalid();
        if(!filter.zone().isEmpty())actor.requireZone(filter.zone());
        List<String> zones=filter.zone().isEmpty()?actor.zones().stream().sorted().toList():List.of(filter.zone());
        var args=new ArrayList<Object>(List.of(actor.tenant(),filter.from(),filter.until()));
        String where=" where t.tenant_id=? and t.created_at>=? and t.created_at<?";
        if(zones.isEmpty())where+=" and 1=0";
        else{where+=" and t.zone in ("+String.join(",",Collections.nCopies(zones.size(),"?"))+")";args.addAll(zones);}
        String[] columns={"owner_id","deployment_id","state","purpose","session_id","task_id"};String[] values={filter.owner(),filter.deployment(),filter.state(),filter.purpose(),filter.session(),filter.task()};
        for(int i=0;i<columns.length;i++)if(!values[i].isEmpty()){where+=" and t."+columns[i]+"=?";args.add(values[i]);}
        return new Query(where,args,actor);
    }
    private ApiException invalid(){return new ApiException(400,"INVALID_MODEL_FILTER","Use a period of at most 93 days and valid filters");}
    private Map<String,Object> result(Query q){var result=new LinkedHashMap<String,Object>();result.put("observedAt",clock.millis());result.put("coverage","ENTERPRISE_ENTRY_ONLY");result.put("zones",q.actor().zones().stream().sorted().toList());return result;}
    private static final String COUNTS="count(*) as calls,count(distinct t.owner_id) as members,sum(case when t.started_at is not null then 1 else 0 end) as attempts,sum(t.input_tokens) as input_tokens,sum(t.output_tokens) as output_tokens,sum(t.cached_tokens) as cached_tokens,sum(case when t.started_at is not null and t.usage_quality<>'COMPLETE' then 1 else 0 end) as incomplete_usage_calls,sum(case when t.state='FAILED' then 1 else 0 end) as failed,sum(case when t.state='SUCCEEDED' then 1 else 0 end) as succeeded,sum(case when t.state='UNKNOWN' then 1 else 0 end) as unknown_calls,avg(case when t.granted_at is not null then t.granted_at-t.created_at end) as average_queue_ms,avg(case when t.first_byte_at is not null then t.first_byte_at-t.started_at end) as average_first_byte_ms";
    private static final String RETRIES=",sum(case when t.started_at is not null then 1 else 0 end)-count(distinct case when t.started_at is not null then t.logical_call_id end) as retry_attempts";
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> overview(Identity actor,Filter filter){
        var q=query(actor,filter);var result=result(q);
        var summary=db.queryForMap("select "+COUNTS+" from model_call_tickets t"+q.where(),q.args().toArray());
        for(String key:List.of("attempts","incomplete_usage_calls","failed","succeeded","unknown_calls"))if(summary.get(key)==null)summary.put(key,0L);
        result.put("summary",summary);result.put("cost",cost(q));
        result.put("hourly",db.queryForList("select floor(t.created_at/3600000.0)*3600000 as hour_start,"+COUNTS+" from model_call_tickets t"+q.where()+" group by floor(t.created_at/3600000.0)*3600000 order by floor(t.created_at/3600000.0)*3600000",q.args().toArray()));return result;
    }
    private Map<String,Object> cost(Query q){
        // Charged is already corrected in the original ledger. Never add adjustments a second time.
        var value=new LinkedHashMap<>(db.queryForMap("select coalesce(sum(case when c.state in ('SETTLED','RELEASED') then c.charged else 0 end),0) as settled_micros,coalesce(sum(case when c.state in ('SETTLED','RELEASED') and c.settlement_basis='PROVIDER_USAGE' then c.charged else 0 end),0) as provider_micros,coalesce(sum(case when c.state in ('SETTLED','RELEASED') and c.settlement_basis='ESTIMATED_MAXIMUM' then c.charged else 0 end),0) as estimated_micros,coalesce(sum(case when c.state in ('SETTLED','RELEASED') and c.settlement_basis='LEGACY' then c.charged else 0 end),0) as legacy_micros,coalesce(sum(case when c.state='HELD' then c.reserved else 0 end),0) as held_micros,coalesce(sum(case when c.state='HELD' and t.state='UNKNOWN' then c.reserved else 0 end),0) as unknown_held_micros,count(case when (c.ticket_id is null or (c.state in ('SETTLED','RELEASED') and c.charged is null)) and t.started_at is not null then 1 end) as unpriced_execution_calls from model_call_tickets t left join model_budget_charges c on c.ticket_id=t.id and c.tenant_id=t.tenant_id and c.owner_id=t.owner_id"+q.where(),q.args().toArray()));
        for(String key:List.of("settled_micros","provider_micros","estimated_micros","legacy_micros","held_micros","unknown_held_micros"))value.put(key,new java.math.BigDecimal(value.get(key).toString()).toBigIntegerExact().toString());
        value.put("currency","CNY");value.put("unit","MICRO_CNY");value.put("timeBasis","CALL_CREATED_AT");value.put("coverage","ENTERPRISE_ENTRY_ONLY");value.put("settledIncludesEstimates",true);return value;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> members(Identity actor,Filter filter,String after,int limit){
        if(after.length()>64||limit<1||limit>100)throw invalid();var q=query(actor,filter);var args=new ArrayList<>(q.args());args.add(after);args.add(limit+1);
        var rows=db.queryForList("select t.owner_id,p.display_name,"+COUNTS+RETRIES+" from model_call_tickets t left join principals p on p.tenant_id=t.tenant_id and p.id=t.owner_id"+q.where()+" and t.owner_id>? group by t.owner_id,p.display_name order by t.owner_id limit ?",args.toArray());
        var result=result(q);if(rows.size()>limit){rows=rows.subList(0,limit);result.put("nextOwner",rows.get(rows.size()-1).get("owner_id"));}result.put("items",rows);return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> calls(Identity actor,Filter filter,long before,String beforeId,int limit){
        if(before<0||beforeId.length()>64||limit<1||limit>100||(before==0&&!beforeId.isEmpty()))throw invalid();var q=query(actor,filter);var args=new ArrayList<>(q.args());String where=q.where();
        if(before>0){where+=" and (t.created_at<? or (t.created_at=? and t.id<?))";args.add(before);args.add(before);args.add(beforeId);}
        args.add(limit+1);
        var rows=db.queryForList("select t.id,t.owner_id,p.display_name,t.zone,t.deployment_id,d.name as deployment_name,t.pool_id,t.call_id,t.logical_call_id,t.device_id,t.session_id,t.task_id,t.parent_task_id,t.purpose,t.state,t.created_at,t.granted_at,t.started_at,t.first_byte_at,t.ended_at,t.input_tokens,t.output_tokens,t.cached_tokens,t.usage_quality,t.outcome,c.reserved as reserved_micros,c.charged as charged_micros,c.state as charge_state,c.settlement_basis,c.tariff_profile,c.tariff_period,c.input_rate,c.cached_rate,c.output_rate from model_call_tickets t left join model_budget_charges c on c.ticket_id=t.id left join principals p on p.id=t.owner_id and p.tenant_id=t.tenant_id left join model_deployments d on d.id=t.deployment_id and d.tenant_id=t.tenant_id"+where+" order by t.created_at desc,t.id desc limit ?",args.toArray());
        var result=result(q);if(rows.size()>limit){rows=rows.subList(0,limit);var last=rows.get(rows.size()-1);result.put("nextCreatedAt",last.get("created_at"));result.put("nextId",last.get("id"));}
        long now=clock.millis();for(var row:rows){long end=row.get("ended_at")==null?now:((Number)row.get("ended_at")).longValue();long created=((Number)row.get("created_at")).longValue();long grant=row.get("granted_at")==null?end:((Number)row.get("granted_at")).longValue();row.put("queue_ms",Math.max(0,grant-created));row.put("inference_ms",row.get("started_at")==null?null:Math.max(0,end-((Number)row.get("started_at")).longValue()));}
        result.put("items",rows);return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> tasks(Identity actor,Filter filter,String afterOwner,String afterSession,String afterTask,int limit){
        if(afterOwner.length()>64||afterSession.length()>128||afterTask.length()>128||limit<1||limit>100)throw invalid();var q=query(actor,filter);var args=new ArrayList<>(q.args());
        args.add(afterOwner);args.add(afterOwner);args.add(afterSession);args.add(afterOwner);args.add(afterSession);args.add(afterTask);args.add(limit+1);
        var rows=db.queryForList("select t.owner_id,p.display_name,t.session_id,t.task_id,min(t.created_at) as first_call_at,max(t.created_at) as last_call_at,"+COUNTS+RETRIES+",sum(case when t.granted_at is not null then t.granted_at-t.created_at else 0 end) as queue_ms,sum(case when t.started_at is not null and t.ended_at is not null then t.ended_at-t.started_at else 0 end) as inference_ms from model_call_tickets t left join principals p on p.id=t.owner_id and p.tenant_id=t.tenant_id"+q.where()+" and (t.session_id<>'' or t.task_id<>'') and (t.owner_id>? or (t.owner_id=? and t.session_id>?) or (t.owner_id=? and t.session_id=? and t.task_id>?)) group by t.owner_id,p.display_name,t.session_id,t.task_id order by t.owner_id,t.session_id,t.task_id limit ?",args.toArray());
        var result=result(q);if(rows.size()>limit){rows=rows.subList(0,limit);var last=rows.get(rows.size()-1);result.put("nextOwner",last.get("owner_id"));result.put("nextSession",last.get("session_id"));result.put("nextTask",last.get("task_id"));}result.put("items",rows);return result;
    }
}
