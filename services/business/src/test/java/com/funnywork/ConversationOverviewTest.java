package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.configuration.Configuration;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationOverviewTest {
    final ObjectMapper json=new ObjectMapper();
    com.fasterxml.jackson.databind.JsonNode event(String type,Object content){return json.valueToTree(Map.of("type",type,"data",Map.of("content",content)));}
    @Test void previewIncludesVisibleTextAndFileNamesButNotHiddenParts(){
        var value=ConversationOverview.preview(event("assistant/message",List.of(Map.of("type","reasoning","text","private reasoning"),Map.of("type","text","text"," hello\n   world "),Map.of("type","file","attachment",Map.of("metadata",Map.of("attachment",Map.of("name","notes.pdf")))))));
        assertEquals("hello world notes.pdf",value.text());assertTrue(value.attachment());
        assertNull(ConversationOverview.preview(event("tool/end",List.of(Map.of("type","text","text","private tool payload")))));
        assertNull(ConversationOverview.preview(json.valueToTree(Map.of("type","user/message","data",Map.of("source",Map.of("kind","tool"),"content",List.of(Map.of("type","text","text","private background prompt")))))));
        assertNull(ConversationOverview.preview(event("assistant/message",List.of(Map.of("type","reasoning","text","private reasoning")))));
        assertTrue(ConversationOverview.preview(event("user/message",List.of(Map.of("type","file","attachment",Map.of())))).attachment());
    }
    @Test void previewBoundsUnicodeWithoutSplittingEmoji(){
        String emoji="😀".repeat(500);
        var preview=ConversationOverview.preview(event("user/message",List.of(Map.of("type","text","text",emoji))));
        assertEquals(400,preview.text().codePointCount(0,preview.text().length()));assertEquals("😀".repeat(400),preview.text());
    }
    @Test void migrationBackfillsLatestVisibleMessageAcrossExistingSessions() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:overview-migration-"+AuthService.id()+";MODE=PostgreSQL")){
            try(var statement=connection.createStatement()){
                statement.execute("create table sync_sessions(id varchar(64) primary key)");
                statement.execute("create table sync_session_events(session_id varchar(64),seq bigint,event_json text,primary key(session_id,seq))");
                statement.execute("insert into sync_sessions(id) values('a'),('b')");
            }
            try(var insert=connection.prepareStatement("insert into sync_session_events values(?,?,?)")){
                int seq=0;
                for(var event:List.of(event("user/message",List.of(Map.of("type","text","text","First"))),event("assistant/message",List.of(Map.of("type","text","text","Latest"))),event("tool/end",List.of(Map.of("type","text","text","hidden"))))){
                    insert.setString(1,"a");insert.setInt(2,seq++);insert.setString(3,json.writeValueAsString(event));insert.executeUpdate();
                }
                insert.setString(1,"b");insert.setInt(2,0);insert.setString(3,json.writeValueAsString(event("assistant/message",List.of(Map.of("type","file","attachment",Map.of("name","result.txt"))))));insert.executeUpdate();
            }
            new db.migration.V70__conversation_overviews().migrate(new Context(){public java.sql.Connection getConnection(){return connection;}public Configuration getConfiguration(){return null;}});
            try(var rows=connection.createStatement().executeQuery("select * from sync_sessions order by id")){
                assertTrue(rows.next());assertEquals("Latest",rows.getString("preview_text"));assertEquals(1,rows.getLong("preview_seq"));
                assertTrue(rows.next());assertEquals("result.txt",rows.getString("preview_text"));assertTrue(rows.getBoolean("preview_attachment"));
            }
        }
    }
}
