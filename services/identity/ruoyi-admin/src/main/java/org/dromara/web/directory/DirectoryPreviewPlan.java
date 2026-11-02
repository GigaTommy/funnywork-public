package org.dromara.web.directory;

import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure projection of the same identity rules used by DirectorySync; contains no credentials. */
final class DirectoryPreviewPlan {
    static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Preview serialization failed");}}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static String sourceHash(DirectoryRegistry.Source source){return hash(json(source));}
    static Map<String,Object> inspect(JdbcTemplate db,DirectoryRegistry.Source source,List<DirectoryAdapter.User> users){
        return inspect(db,source,users,false);
    }
    static Map<String,Object> inspect(JdbcTemplate db,DirectoryRegistry.Source source,List<DirectoryAdapter.User> users,boolean lock){
        String suffix=lock?" for update":"";
        var items=new ArrayList<Map<String,Object>>();var observed=new ArrayList<Object>();
        int added=0,updated=0,disabled=0,skipped=0,conflicts=0;
        for(var user:users){
            Long department=source.departments().get(user.departmentId());
            var depts=department==null?List.of():db.queryForList("select dept_id,status,del_flag from sys_dept where tenant_id=? and dept_id=?"+suffix,source.tenantId(),department);
            var links=db.queryForList("select user_id,remote_version,content_hash,remote_enabled from fw_directory_users where tenant_id=? and source_id=? and external_id=?"+suffix,source.tenantId(),source.id(),user.externalId());
            Long id=links.isEmpty()?null:((Number)links.get(0).get("user_id")).longValue();
            var accounts=id==null?List.<Map<String,Object>>of():db.queryForList("select user_id,user_name,nick_name,dept_id,status,del_flag,external_source from sys_user where tenant_id=? and user_id=?"+suffix,source.tenantId(),id);
            var collisions=db.queryForList("select user_id from sys_user where tenant_id=? and user_name=? and del_flag='0' order by user_id"+suffix,source.tenantId(),user.username());
            observed.add(List.of(depts,links,accounts,collisions));
            String action=id==null?"ADD":"UPDATE",reason="";
            if(depts.isEmpty()||!"0".equals(((Map<?,?>)depts.get(0)).get("status"))||!"0".equals(((Map<?,?>)depts.get(0)).get("del_flag")))reason="SYNC_DEPARTMENT_UNMAPPED";
            if(reason.isEmpty()&&id!=null){
                long version=((Number)links.get(0).get("remote_version")).longValue();
                if(user.version()<version)action="SKIP";
                else if(user.version()==version){if(!DirectorySync.hash(user).equals(links.get(0).get("content_hash")))reason="SYNC_USER_VERSION_CONFLICT";else action="SKIP";}
            }
            if(reason.isEmpty()&&!action.equals("SKIP")){
                for(var row:collisions)if(!Objects.equals(id,((Number)row.get("user_id")).longValue()))reason="SYNC_USERNAME_CONFLICT";
                if(id!=null&&(accounts.isEmpty()||!"0".equals(accounts.get(0).get("del_flag"))||!source.id().equals(accounts.get(0).get("external_source"))))reason="SYNC_ACCOUNT_CONFLICT";
            }
            if(!reason.isEmpty()){action="CONFLICT";conflicts++;}
            else if(action.equals("SKIP"))skipped++;
            else if(action.equals("ADD"))added++;
            else {updated++;if(!user.enabled()&&"0".equals(accounts.get(0).get("status"))){action="DISABLE";disabled++;}}
            var item=new LinkedHashMap<String,Object>();item.put("externalId",user.externalId());item.put("username",user.username());item.put("displayName",user.displayName());item.put("departmentId",user.departmentId());item.put("enabled",user.enabled());item.put("action",action);item.put("reason",reason);item.put("before",accounts.isEmpty()?Map.of():accounts.get(0));items.add(item);
        }
        return Map.of("added",added,"updated",updated,"disabled",disabled,"skipped",skipped,"conflicts",conflicts,"items",items,"baseline",hash(json(observed)));
    }
}
