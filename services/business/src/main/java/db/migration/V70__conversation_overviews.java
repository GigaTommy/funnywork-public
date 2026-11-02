package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funnywork.ConversationOverview;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Backfill existing histories once, streaming records and retaining only one session excerpt. */
public class V70__conversation_overviews extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        try(var statement=connection.createStatement()){
            statement.execute("ALTER TABLE sync_sessions ADD COLUMN preview_text VARCHAR(800)");
            statement.execute("ALTER TABLE sync_sessions ADD COLUMN preview_seq BIGINT");
            statement.execute("ALTER TABLE sync_sessions ADD COLUMN preview_attachment BOOLEAN NOT NULL DEFAULT FALSE");
        }
        var json=new ObjectMapper();
        try(var select=connection.prepareStatement("select session_id,seq,event_json from sync_session_events order by session_id,seq");
            var update=connection.prepareStatement("update sync_sessions set preview_text=?,preview_seq=?,preview_attachment=? where id=?")){
            select.setFetchSize(32);
            String session=null;Long sequence=null;ConversationOverview.Preview preview=null;
            try(var rows=select.executeQuery()){
                while(rows.next()){
                    String next=rows.getString(1);
                    if(!next.equals(session)){
                        if(preview!=null)write(update,session,sequence,preview);
                        session=next;sequence=null;preview=null;
                    }
                    var candidate=ConversationOverview.preview(json.readTree(rows.getString(3)));
                    if(candidate!=null){preview=candidate;sequence=rows.getLong(2);}
                }
            }
            if(preview!=null)write(update,session,sequence,preview);
        }
    }
    private void write(java.sql.PreparedStatement statement,String session,Long sequence,ConversationOverview.Preview preview) throws Exception {
        statement.setString(1,preview.text());statement.setLong(2,sequence);statement.setBoolean(3,preview.attachment());statement.setString(4,session);statement.executeUpdate();
    }
}
