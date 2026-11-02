package com.funnywork;

import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Counts server task records; never treats task activity as billable model consumption. */
@Service
public class ManagementUsage {
 private final JdbcTemplate db;private final AuthService auth;private final Clock clock;
 public ManagementUsage(JdbcTemplate db,AuthService auth,Clock clock){this.db=db;this.auth=auth;this.clock=clock;}
 @Transactional(readOnly=true)
 public Map<String,Object> read(Identity actor,long from,long until,String after,int limit){
  actor=auth.current(actor);actor.requireUser("ADMIN");
  if(from<0||until<=from||until-from>93L*86400000||after==null||!after.matches("[A-Za-z0-9-]{0,64}")||limit<1||limit>100)throw new ApiException(400,"INVALID_USAGE_FILTER","Choose a period of at most 93 days");
  var result=new LinkedHashMap<String,Object>();result.put("from",from);result.put("until",until);result.put("observedAt",clock.millis());result.put("scope","AUTHORIZED_ZONES");result.put("zones",actor.zones());
  result.put("modelConsumption",Map.of("coverage","UNAVAILABLE","reason","NO_TRUSTED_TOKEN_LEDGER","budgetEnforcementAvailable",false));
  result.put("inputTokens",null);result.put("outputTokens",null);result.put("cost",null);
  if(actor.zones().isEmpty()){result.put("summary",Map.of("tasks",0,"members",0,"failed",0,"succeeded",0));result.put("items",List.of());return result;}
  var params=new ArrayList<Object>(List.of(actor.tenant(),from,until));params.addAll(actor.zones());
  String where=" where t.tenant_id=? and t.created_at>=? and t.created_at<? and t.zone in ("+String.join(",",Collections.nCopies(actor.zones().size(),"?"))+")";
  String counts="count(*) as tasks,sum(case when t.state='FAILED' then 1 else 0 end) as failed,sum(case when t.state='SUCCEEDED' then 1 else 0 end) as succeeded";
  var total=new LinkedHashMap<>(db.queryForMap("select "+counts+",count(distinct t.owner_id) as members from tasks t"+where,params.toArray()));
  total.replaceAll((key,value)->value==null?0:value);result.put("summary",total);
  params.add(after);params.add(limit+1);
  var rows=db.queryForList("select p.id,p.display_name,"+counts+" from tasks t join principals p on p.id=t.owner_id and p.tenant_id=t.tenant_id"+where+" and p.id>? group by p.id,p.display_name order by p.id limit ?",params.toArray());
  boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);result.put("items",rows);
  if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
 }
}
