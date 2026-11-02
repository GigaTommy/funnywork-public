package org.dromara.web.directory;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Organization-scoped batch metadata; never reads account snapshots or upstream credentials. */
@Service
public class DirectoryAudit {
    private final JdbcTemplate db;
    private final DirectoryAccess access;
    public DirectoryAudit(JdbcTemplate db, DirectoryAccess access) { this.db=db; this.access=access; }
    public Map<String,Object> page(String tenant,long actor,long from,long until,String source,String reference,String cursor,int limit) {
        access.require(tenant,actor,false);
        if(from<0||until<=from||until-from>93L*86400000||limit<1||limit>100)throw invalid();
        source=source==null?"":source.trim();reference=reference==null?"":reference.trim();
        if(!source.matches("[A-Za-z0-9_.-]{0,64}")||!reference.matches("[A-Za-z0-9-]{0,36}"))throw invalid();
        // A job and each of its attempts are separate records: retries must not hide earlier runs.
        String sql="select * from (select 'JOB' as kind,j.id,j.source_id,cast(j.actor_id as varchar(30)) as actor_id,j.created_at,j.state,j.run_id,j.error_code,"
            +"j.full_sync,j.preview_only,j.attempts,cast(null as integer) as added,cast(null as integer) as updated,cast(null as integer) as skipped,"
            +"cast(null as varchar(16)) as preview_state,cast(null as varchar(30)) as approved_by,cast(null as bigint) as applied_at,cast(null as bigint) as expires_at "
            +"from fw_directory_jobs j where j.tenant_id=? union all "
            +"select 'RUN',r.id,r.source_id,cast(r.actor_id as varchar(30)),r.created_at,r.state,r.id,r.error_code,"
            +"r.mode='FULL',p.id is not null,cast(null as integer),r.added,r.updated,r.skipped,p.state,cast(p.approved_by as varchar(30)),p.applied_at,p.expires_at "
            +"from fw_directory_runs r left join fw_directory_previews p on p.id=r.id and p.tenant_id=r.tenant_id and p.source_id=r.source_id where r.tenant_id=?) a "
            +"where created_at>=? and created_at<?";
        List<Object> args=new ArrayList<>(List.of(tenant,tenant,from,until));
        if(!source.isEmpty()){sql+=" and source_id=?";args.add(source);}
        if(!reference.isEmpty()){sql+=" and (id=? or run_id=?)";args.add(reference);args.add(reference);}
        if(cursor!=null&&!cursor.isEmpty()){
            if(cursor.length()>200)throw invalid();
            String[] parts;
            long timestamp;
            try {parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);if(parts.length!=3)throw invalid();timestamp=Long.parseLong(parts[0]);}
            catch(IllegalArgumentException e){throw invalid();}
            if(timestamp<from||timestamp>=until||!Set.of("JOB","RUN").contains(parts[1])||!parts[2].matches("[A-Za-z0-9-]{1,36}"))throw invalid();
            sql+=" and (created_at<? or (created_at=? and kind<?) or (created_at=? and kind=? and id<?))";
            args.addAll(List.of(timestamp,timestamp,parts[1],timestamp,parts[1],parts[2]));
        }
        sql+=" order by created_at desc,kind desc,id desc limit ?";args.add(limit+1);
        List<Map<String,Object>> rows=db.queryForList(sql,args.toArray());
        boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));
        String next="";
        if(more){var last=rows.get(rows.size()-1);next=Base64.getUrlEncoder().withoutPadding().encodeToString((last.get("created_at")+"|"+last.get("kind")+"|"+last.get("id")).getBytes(StandardCharsets.UTF_8));}
        return Map.of("items",rows,"nextCursor",next,"observedAt",System.currentTimeMillis(),"basis","CURRENT_BATCH_STATE");
    }
    private ServiceException invalid(){return new ServiceException("查询条件无效，请选择最多 93 天的时间范围及有效批次编号",400);}
}
