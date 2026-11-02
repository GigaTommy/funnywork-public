package com.funnywork;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;
import java.util.List;

/** Immutable chunks fetched with SQL limits; bounded even on autocommit PostgreSQL connections. */
@Component
public class JdbcResultBlobReader implements ResultBlobReader {
    private final JdbcTemplate db;
    private record Chunk(long offset, String encoded) {}
    public JdbcResultBlobReader(JdbcTemplate db){this.db=db;}
    public boolean verified(Identity actor,String hash,long size){
        var rows=db.queryForList("select size_bytes from sync_attachment_objects where tenant_id=? and principal_id=? and hash=? and verified=true",actor.tenant(),actor.id(),hash);
        if(rows.isEmpty())return false;
        if(((Number)rows.get(0).get("size_bytes")).longValue()!=size)throw ApiException.conflict("RESULT_CONTENT_CHANGED");
        return true;
    }
    public void write(Identity actor,String hash,long first,long last,OutputStream output)throws IOException{
        if(first>last)return;
        // Find the containing chunk directly: tiny uploaded chunks must not require a backward scan.
        List<Chunk> page=db.query("select byte_offset,encoded_data from sync_attachment_chunks where tenant_id=? and principal_id=? and hash=? and byte_offset<=? order by byte_offset desc limit 1",
            (row,index)->new Chunk(row.getLong(1),row.getString(2)),actor.tenant(),actor.id(),hash,first);
        long cursor=first;
        while(cursor<=last){
            if(page.isEmpty())throw new IOException("Result content is truncated");
            long nextOffset=-1;
            for(var chunk:page){
                byte[] bytes=Base64.getDecoder().decode(chunk.encoded());
                if(chunk.offset()>cursor||cursor-chunk.offset()>=bytes.length)throw new IOException("Result content has a gap");
                int from=(int)(cursor-chunk.offset()),count=(int)Math.min(bytes.length-from,last-cursor+1);
                output.write(bytes,from,count);cursor+=count;nextOffset=chunk.offset();
                if(cursor>last)return;
            }
            page=db.query("select byte_offset,encoded_data from sync_attachment_chunks where tenant_id=? and principal_id=? and hash=? and byte_offset>? and byte_offset<=? order by byte_offset limit 2",
                (row,index)->new Chunk(row.getLong(1),row.getString(2)),actor.tenant(),actor.id(),hash,nextOffset,last);
        }
    }
}
