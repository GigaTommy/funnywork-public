package com.funnywork;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcResultBlobReaderTest {
    static class TrackingJdbc extends JdbcTemplate {
        final List<Integer> pageSizes=new ArrayList<>();
        TrackingJdbc(DriverManagerDataSource source){super(source);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){
            var rows=super.query(sql,mapper,args);pageSizes.add(rows.size());return rows;
        }
    }
    TrackingJdbc db;JdbcResultBlobReader reader;Identity owner;
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        db=new TrackingJdbc(source);reader=new JdbcResultBlobReader(db);
        owner=new Identity("owner","tenant","USER",Set.of("VIEWER"),Set.of("sandbox"),0);
        db.execute("create table sync_attachment_chunks(tenant_id varchar,principal_id varchar,hash varchar,byte_offset bigint,encoded_data varchar)");
    }
    byte[] populate(int chunks,int size){
        byte[] original=new byte[chunks*size];for(int i=0;i<original.length;i++)original[i]=(byte)(i%251);
        for(int i=0;i<chunks;i++)db.update("insert into sync_attachment_chunks values(?,?,?,?,?)","tenant","owner","hash",i*size,Base64.getEncoder().encodeToString(Arrays.copyOfRange(original,i*size,(i+1)*size)));
        return original;
    }
    @Test void autocommitDownloadsNeverBufferMoreThanTwoRows()throws Exception{
        byte[] original=populate(20,384000);assertTrue(Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->connection.getAutoCommit())));
        var output=new ByteArrayOutputStream();reader.write(owner,"hash",0,original.length-1,output);assertArrayEquals(original,output.toByteArray());
        assertTrue(db.pageSizes.size()>2);assertEquals(1,db.pageSizes.get(0));assertTrue(db.pageSizes.stream().allMatch(size->size<=2));
    }
    @Test void rangesSeekDirectlyEvenWithTinyChunksAndClipBothEnds()throws Exception{
        byte[] original=populate(30,3);var output=new ByteArrayOutputStream();reader.write(owner,"hash",70,82,output);
        assertArrayEquals(Arrays.copyOfRange(original,70,83),output.toByteArray());assertTrue(db.pageSizes.size()<=3);
        assertTrue(db.pageSizes.stream().allMatch(size->size<=2));
    }
    @Test void emptyFilesDoNotQueryAndBrokenStreamsStopImmediately()throws Exception{
        reader.write(owner,"hash",0,-1,new ByteArrayOutputStream());assertTrue(db.pageSizes.isEmpty());
        populate(10,4);assertThrows(IOException.class,()->reader.write(owner,"hash",0,39,new OutputStream(){public void write(int value)throws IOException{throw new IOException("client disconnected");}}));
        assertEquals(List.of(1),db.pageSizes);
    }
    @Test void missingChunksCannotBeSilentlySkipped(){
        populate(3,4);db.update("delete from sync_attachment_chunks where byte_offset=4");
        assertThrows(IOException.class,()->reader.write(owner,"hash",0,11,new ByteArrayOutputStream()));
    }
}
