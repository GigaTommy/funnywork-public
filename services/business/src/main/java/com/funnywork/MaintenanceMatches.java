package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Same-repository, same-platform retrieval and canonical duplicate resolution. */
@Service
public class MaintenanceMatches {
    private final JdbcTemplate db;private final Json json;
    public MaintenanceMatches(JdbcTemplate db,Json json){this.db=db;this.json=json;}
    private static final Set<String> ELIGIBLE=Set.of("RECEIVED","ANALYZING","NEEDS_INFORMATION","READY_FOR_REVIEW","ADMITTED");
    public Map<String,Object> target(Map<String,Object> source,String id){
        if(id.equals(source.get("id")))throw ApiException.conflict("MAINTENANCE_DUPLICATE_INVALID");
        var rows=db.queryForList("select i.*,p.state as repair_state,q.spec_json,q.version as requirement_version from maintenance_intakes i left join maintenance_problems p on p.id=i.problem_id left join maintenance_requirements q on q.intake_id=i.id where i.id=? and i.tenant_id=? and i.repository_id=? and i.platform=?",id,source.get("tenant_id"),source.get("repository_id"),source.get("platform"));
        if(rows.isEmpty()||!ELIGIBLE.contains(rows.get(0).get("state"))||(!"ADMITTED".equals(rows.get(0).get("state"))&&rows.get(0).get("spec_json")==null))throw ApiException.conflict("MAINTENANCE_DUPLICATE_INVALID");var target=rows.get(0);if("ADMITTED".equals(target.get("state"))&&!Set.of("IN_PROGRESS","AWAITING_ACCEPTANCE","BLOCKED","ACCEPTED","DELIVERED").contains(String.valueOf(target.get("repair_state"))))throw ApiException.conflict("MAINTENANCE_DUPLICATE_INVALID");return target;
    }
    public List<Map<String,Object>> feedbacks(String intake){
        var values=new LinkedHashMap<String,Map<String,Object>>();
        for(var source:db.queryForList("select distinct payload_json from maintenance_intake_sources where intake_id=? and source_kind='CLIENT' order by payload_json",intake)){var payload=(Map<?,?>)json.read((String)source.get("payload_json"));if(payload.get("clientFeedback") instanceof Map<?,?> fields&&fields.get("expected") instanceof String expected)values.putIfAbsent(expected,Map.of("expected",expected));else if(payload.get("body") instanceof String text)values.putIfAbsent(text,Map.of("expected",text,"legacyUnstructured",true));}
        for(String content:db.queryForList("select content from maintenance_feedback_messages where intake_id=? and review_state='APPLIED' order by created_at,id",String.class,intake))values.putIfAbsent(content,Map.of("expected",content));
        if(values.size()>80||values.keySet().stream().mapToInt(String::length).sum()>40000)throw ApiException.conflict("MAINTENANCE_SOURCE_CONTEXT_TOO_LARGE");return new ArrayList<>(values.values());
    }
    public Map<String,Object> comparison(Map<String,Object> value){var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","title","body","platform","revision","state","spec_json","requirement_version","repair_state"))if(value.containsKey(key))result.put(key,value.get(key));return result;}
    public List<String> versions(String intake){return db.queryForList("select distinct client_version from maintenance_intake_sources where intake_id=? and source_kind='CLIENT' and client_version<>'' order by client_version",String.class,intake);}
    public boolean historical(Map<String,Object> target){return Set.of("ACCEPTED","DELIVERED").contains(String.valueOf(target.get("repair_state")));}
    public boolean comparableHistory(Map<String,Object> source,Map<String,Object> target){if(!historical(target))return true;var before=versions((String)target.get("id"));var current=versions((String)source.get("id"));return !current.isEmpty()&&!before.isEmpty()&&before.containsAll(current);}
    public List<Map<String,Object>> candidates(Map<String,Object> source){
        String base="select i.id,i.title,i.body,i.state,i.platform,p.state as repair_state,q.spec_json,q.version as requirement_version from maintenance_intakes i left join maintenance_problems p on p.id=i.problem_id left join maintenance_requirements q on q.intake_id=i.id where i.tenant_id=? and i.repository_id=? and i.platform=? and i.id<>? and i.state in ('RECEIVED','ANALYZING','NEEDS_INFORMATION','READY_FOR_REVIEW','ADMITTED') and (i.state='ADMITTED' or q.id is not null)";
        var args=new ArrayList<Object>(List.of(source.get("tenant_id"),source.get("repository_id"),source.get("platform"),source.get("id")));
        String title=String.valueOf(source.get("title"));var terms=new LinkedHashSet<String>();for(String part:title.split("[^\\p{L}\\p{N}]+"))if(part.length()>1){if(part.matches(".*[\\p{IsHan}].*")&&part.length()>3){for(int n=0;n+2<=part.length()&&terms.size()<6;n+=2)terms.add(part.substring(n,n+2));}else if(terms.size()<6)terms.add(part);}
        var results=new LinkedHashMap<String,Map<String,Object>>();
        if(!terms.isEmpty()){String filter=String.join(" or ",Collections.nCopies(terms.size(),"position(lower(?) in lower(i.title))>0"));var search=new ArrayList<>(args);search.addAll(terms);for(var row:db.queryForList(base+" and ("+filter+") order by i.updated_at desc,i.id limit 20",search.toArray()))results.put((String)row.get("id"),row);}
        for(var row:db.queryForList(base+" order by i.updated_at desc,i.id limit 20",args.toArray()))results.putIfAbsent((String)row.get("id"),row);
        var versionMap=new HashMap<String,List<String>>();if(!results.isEmpty())for(var sourceRow:db.queryForList("select distinct intake_id,client_version from maintenance_intake_sources where source_kind='CLIENT' and client_version<>'' and intake_id in ("+String.join(",",Collections.nCopies(results.size(),"?"))+") order by client_version",results.keySet().toArray()))versionMap.computeIfAbsent((String)sourceRow.get("intake_id"),key->new ArrayList<>()).add((String)sourceRow.get("client_version"));
        var out=new ArrayList<Map<String,Object>>();for(var row:results.values()){var compact=new LinkedHashMap<>(row);compact.put("body",clip(row.get("body"),1600));compact.put("truncated",String.valueOf(row.get("body")).length()>1600||String.valueOf(row.get("spec_json")).length()>2400);if(row.get("spec_json")!=null)compact.put("product",clip(row.get("spec_json"),2400));compact.remove("spec_json");compact.put("clientVersions",versionMap.getOrDefault((String)row.get("id"),List.of()));out.add(compact);}return out;
    }
    private String clip(Object value,int max){String text=String.valueOf(value);return text.length()<=max?text:text.substring(0,max);}
    /** DTO reads resolve a queued duplicate after its canonical requirement has been dispatched. */
    public Map<String,Object> canonical(Map<String,Object> intake){
        if(!"DUPLICATE".equals(intake.get("state"))||intake.get("duplicate_of")==null)return intake;
        var rows=db.queryForList("select * from maintenance_intakes where id=? and tenant_id=? and repository_id=? and platform=? and state<>'DUPLICATE'",intake.get("duplicate_of"),intake.get("tenant_id"),intake.get("repository_id"),intake.get("platform"));return rows.isEmpty()?intake:rows.get(0);
    }
}
