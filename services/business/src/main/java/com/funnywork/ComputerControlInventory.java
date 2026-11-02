package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

/** Operational metadata only. Expiration never certifies that a desktop driver stopped. */
@Service
public class ComputerControlInventory {
    private final JdbcTemplate db;private final AuthService auth;private final Clock clock;
    public ComputerControlInventory(JdbcTemplate db,AuthService auth,Clock clock){this.db=db;this.auth=auth;this.clock=clock;}
    public Map<String,Object> page(Identity actor,String node,String after,int limit,boolean expiredOnly){
        actor=auth.current(actor);actor.requireUser("ADMIN");
        if(node==null || after==null || (!node.isEmpty() && !node.matches("[A-Za-z0-9_-]{1,64}"))
            || after.length()>64 || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid desktop control page");
        long now=clock.millis();
        if(actor.zones().isEmpty())return Map.of("items",List.of(),"observedAt",now,"consistentSnapshot",false);
        var zones=new TreeSet<>(actor.zones());String placeholders=String.join(",",Collections.nCopies(zones.size(),"?"));
        List<Object> params=new ArrayList<>(List.of(actor.tenant(),after));params.addAll(zones);params.addAll(zones);
        String filter="";
        if(!node.isEmpty()){filter+=" and c.node_id=?";params.add(node);}
        if(expiredOnly){filter+=" and c.expires_at<=?";params.add(now);}
        params.add(limit+1);
        var rows=db.queryForList("""
            select c.attempt_id,c.node_id,c.os_session,c.generation,c.epoch,c.expires_at,
                a.unit_id,a.state as attempt_state,u.task_id,t.state as task_state,t.zone,
                n.status as node_status,n.last_seen,n.admin_paused,p.enabled as node_enabled,
                (select count(*) from computer_actions x where x.tenant_id=c.tenant_id and x.unit_id=u.id and x.state='UNKNOWN') as unknown_actions,
                (select count(*) from computer_actions x where x.tenant_id=c.tenant_id and x.unit_id=u.id and x.state='DISPATCHED') as dispatched_actions
            from computer_controls c join attempts a on a.id=c.attempt_id and a.tenant_id=c.tenant_id
                join work_units u on u.id=a.unit_id and u.tenant_id=c.tenant_id
                join tasks t on t.id=u.task_id and t.tenant_id=c.tenant_id
                join nodes n on n.id=c.node_id and n.tenant_id=c.tenant_id
                join principals p on p.id=n.id and p.tenant_id=c.tenant_id
            where c.tenant_id=? and c.attempt_id>? and c.state='ACTIVE'
            """+" and n.zone in ("+placeholders+") and t.zone in ("+placeholders+")"+filter+" order by c.attempt_id limit ?",params.toArray());
        boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var items=new ArrayList<Map<String,Object>>();
        for(var row:rows){
            var item=new LinkedHashMap<String,Object>();
            String[][] names={{"attempt_id","attemptId"},{"node_id","nodeId"},{"os_session","session"},{"generation","generation"},
                {"epoch","epoch"},{"expires_at","controlExpiresAt"},{"unit_id","unitId"},{"task_id","taskId"},{"zone","zone"},
                {"attempt_state","attemptState"},{"task_state","taskState"},{"node_status","nodeStatus"},{"last_seen","lastSeenAt"},
                {"admin_paused","adminPaused"},{"node_enabled","nodeEnabled"},{"unknown_actions","unknownActions"},{"dispatched_actions","dispatchedActions"}};
            for(var name:names)item.put(name[1],row.get(name[0]));
            item.put("leaseExpired",((Number)row.get("expires_at")).longValue()<=now);
            item.put("requiresStopConfirmation",true);items.add(item);
        }
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("observedAt",now);result.put("consistentSnapshot",false);
        if(more)result.put("nextCursor",rows.get(rows.size()-1).get("attempt_id"));
        return result;
    }
}
