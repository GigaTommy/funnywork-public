package com.funnywork;

import com.fasterxml.jackson.databind.*;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/** Independent text conversations; inference always uses the shared admission and token ledger. */
@Service
public class MobileChats {
    public record Send(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String id,
                       @NotBlank @Size(max=64) String modelId,@NotBlank @Size(max=16000) String text,@Min(0) long expectedRevision,
                       @Pattern(regexp="off|low|high|max") String reasoningEffort, @Min(128) @Max(262144) Integer maxOutputTokens) {}
    private final JdbcTemplate db;private final AuthService auth;private final ModelMiddleware models;
    private final ObjectMapper json;private final Clock clock;private final TransactionTemplate tx;
    public MobileChats(JdbcTemplate db,AuthService auth,ModelMiddleware models,ObjectMapper json,Clock clock,PlatformTransactionManager manager){this.db=db;this.auth=auth;this.models=models;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager);}
    private Identity user(Identity actor){var live=auth.current(actor);if(!"USER".equals(live.kind()))throw ApiException.forbidden();return live;}
    private Map<String,Object> chat(Identity actor,String id,boolean lock){var rows=db.queryForList("select * from mobile_chats where id=? and tenant_id=? and owner_id=?"+(lock?" for update":""),id,actor.tenant(),actor.id());if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("Invalid chat data");}}
    public List<Map<String,Object>> catalog(Identity actor){return models.chatCatalog(user(actor)).stream().map(row->{
        var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("name",row.get("name"));item.put("model",row.get("model"));
        item.put("provider_name",row.getOrDefault("provider_name","DeepSeek"));item.put("protocol",row.get("protocol"));item.put("available",!Boolean.TRUE.equals(row.get("paused")));
        item.put("reasoningEfforts",MobileModelThinking.supported((String)row.get("model"),row.get("capabilities"))?MobileModelThinking.LEVELS:List.of());
        item.put("defaultReasoningEffort","off");return (Map<String,Object>)item;
    }).toList();}
    public List<Map<String,Object>> list(Identity actor){var live=user(actor);return db.queryForList("select id,title,model_id,revision,updated_at,archived,history_revision from mobile_chats where tenant_id=? and owner_id=? and archived=false order by updated_at desc,id limit 100",live.tenant(),live.id());}
    public Map<String,Object> snapshot(Identity actor,String id){var live=user(actor);var c=chat(live,id,false);
        // Repair terminal ledger records whose reply was never committed; never replay inference.
        db.update("update mobile_chat_turns set state='FAILED' where chat_id=? and state='PREPARED' and ticket_id in (select id from model_call_tickets where tenant_id=? and owner_id=? and state='SUCCEEDED')",id,live.tenant(),live.id());
        var turns=db.queryForList("select t.id,t.user_text,t.reply_text,t.state,t.ticket_id,t.ordinal,k.state as ticket_state,k.input_tokens,k.output_tokens,k.cached_tokens,k.created_at as admitted_at,k.granted_at,k.started_at,k.first_byte_at,k.first_output_at,k.ended_at,k.usage_quality,d.name as model_name,d.model as model from mobile_chat_turns t join model_call_tickets k on k.id=t.ticket_id and k.tenant_id=? and k.owner_id=? join model_deployments d on d.id=k.deployment_id and d.tenant_id=k.tenant_id where t.chat_id=? order by t.ordinal",live.tenant(),live.id(),id);
        for(var turn:turns)turn.put("performance",ModelCallTiming.of(turn));
        Map<String,Object> ticket=turns.isEmpty()?Map.of():models.readTicket(live,(String)turns.get(turns.size()-1).get("ticket_id"));
        return Map.of("chat",MobileChatHistory.summary(c),"turns",turns,"ticket",ticket);}

    public Map<String,Object> send(Identity actor,String chatId,Send body){
        var live=user(actor);if(!chatId.matches("[A-Za-z0-9_-]{1,64}"))throw new ApiException(400,"INVALID_CHAT","Invalid conversation");
        var identity=new LinkedHashMap<String,Object>(Map.of("model",body.modelId(),"text",body.text(),"revision",body.expectedRevision()));
        if(body.reasoningEffort()!=null)identity.put("reasoningEffort",body.reasoningEffort());
        if(body.maxOutputTokens()!=null)identity.put("maxOutputTokens",body.maxOutputTokens());
        String hash=ModelMiddleware.requestDigest(encode(identity).getBytes(StandardCharsets.UTF_8));
        var prior=db.queryForList("select t.request_hash,t.ticket_id from mobile_chat_turns t join mobile_chats c on c.id=t.chat_id where t.id=? and c.id=? and c.tenant_id=? and c.owner_id=?",body.id(),chatId,live.tenant(),live.id());
        if(!prior.isEmpty()){if(!hash.equals(prior.get(0).get("request_hash")))throw ApiException.conflict("CHAT_SEND_CONFLICT");return Map.of("turnId",body.id(),"ticket",models.readTicket(live,(String)prior.get(0).get("ticket_id")));}
        var route=models.chatCatalog(live).stream().filter(row->body.modelId().equals(row.get("id"))).findFirst().orElseThrow(()->new ApiException(409,"CHAT_MODEL_UNAVAILABLE","Select an available chat model"));
        tx.executeWithoutResult(status->{
            db.queryForList("select id from principals where tenant_id=? and id=? for update",live.tenant(),live.id());user(live);
            var priorChat=db.queryForList("select * from mobile_chats where id=?",chatId);
            if(priorChat.isEmpty()){if(body.expectedRevision()!=0)throw ApiException.conflict("CHAT_REVISION_CONFLICT");String title=body.text().strip().replaceAll("\s+"," ");title=title.substring(0,title.offsetByCodePoints(0,Math.min(title.codePointCount(0,title.length()),80)));db.update("insert into mobile_chats(id,tenant_id,owner_id,title,model_id,revision,created_at,updated_at) values(?,?,?,?,?,0,?,?)",chatId,live.tenant(),live.id(),title,body.modelId(),clock.millis(),clock.millis());}
            else chat(live,chatId,false);
        });
        return tx.execute(status->{
            var c=chat(live,chatId,true);var duplicate=db.queryForList("select * from mobile_chat_turns where id=?",body.id());
            if(!duplicate.isEmpty()){var old=duplicate.get(0);if(!chatId.equals(old.get("chat_id")))throw ApiException.missing();if(!hash.equals(old.get("request_hash")))throw ApiException.conflict("CHAT_SEND_CONFLICT");return Map.of("turnId",body.id(),"ticket",models.readTicket(live,(String)old.get("ticket_id")));}
            if(((Number)c.get("revision")).longValue()!=body.expectedRevision())throw ApiException.conflict("CHAT_REVISION_CONFLICT");
            if(Boolean.TRUE.equals(c.get("archived")))throw ApiException.conflict("CHAT_ARCHIVED");
            var history=db.queryForList("select * from mobile_chat_turns where chat_id=? order by ordinal",chatId);if(history.size()>=100)throw ApiException.conflict("CHAT_CONTEXT_FULL");
            var messages=new ArrayList<Map<String,String>>();for(var turn:history){var ticket=models.readTicket(live,(String)turn.get("ticket_id"));if("PREPARED".equals(turn.get("state"))&&"SUCCEEDED".equals(ticket.get("state"))||Set.of("WAITING","GRANTED","RUNNING","STOPPING","UNKNOWN").contains(ticket.get("state")))throw ApiException.conflict("CHAT_TURN_ACTIVE");if("SUCCEEDED".equals(turn.get("state"))&&!((String)turn.get("reply_text")).isBlank()){messages.add(Map.of("role","user","content",(String)turn.get("user_text")));messages.add(Map.of("role","assistant","content",(String)turn.get("reply_text")));}}
            messages.add(Map.of("role","user","content",body.text()));int output=((Number)route.get("max_output_tokens")).intValue();
            if(body.maxOutputTokens()!=null){if(body.maxOutputTokens()>output)throw new ApiException(400,"CHAT_OUTPUT_UNAVAILABLE","Requested output exceeds model allowance");output=body.maxOutputTokens();}
            var request=new LinkedHashMap<String,Object>(Map.of("model",route.get("model"),"messages",messages,"stream",true,"max_tokens",output));
            var capabilities=json.valueToTree(route.get("capabilities"));
            MobileModelThinking.apply(request,route,body.reasoningEffort());
            if("CHAT_COMPLETIONS".equals(route.get("protocol"))&&capabilities.path("streamUsage").asBoolean(false))request.put("stream_options",Map.of("include_usage",true));
            String payload=encode(request);byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>ModelDefaults.MAX_REQUEST_BYTES||encode(messages).getBytes(StandardCharsets.UTF_8).length>Math.max(64,((Number)route.get("context_tokens")).longValue()-output))throw ApiException.conflict("CHAT_CONTEXT_FULL");
            var ticket=models.admit(live,new ModelMiddleware.Ticket(body.modelId(),"chat:"+body.id(),"chat:"+body.id(),"mobile-chat",chatId,chatId,"","mobile-chat",ModelMiddleware.requestDigest(bytes),output));String ticketId=(String)ticket.get("id");
            db.update("insert into mobile_chat_turns values(?,?,?,?,?,'',?,?,'PREPARED',?)",body.id(),chatId,history.size(),hash,body.text(),payload,ticketId,clock.millis());
            db.update("update mobile_chats set revision=revision+1,model_id=?,updated_at=? where id=?",body.modelId(),clock.millis(),chatId);
            return Map.of("turnId",body.id(),"ticket",ticket);
        });
    }
    private Map<String,Object> turn(Identity actor,String chatId,String turnId){var live=user(actor);chat(live,chatId,false);var rows=db.queryForList("select * from mobile_chat_turns where id=? and chat_id=?",turnId,chatId);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);}
    public byte[] payload(Identity actor,String id,String turnId){return ((String)turn(actor,id,turnId).get("request_json")).getBytes(StandardCharsets.UTF_8);}
    public String ticket(Identity actor,String id,String turnId){return (String)turn(actor,id,turnId).get("ticket_id");}
    public void cancel(Identity actor,String id,String turnId){models.cancel(user(actor),ticket(actor,id,turnId),false);}
    public ModelInferenceProxy.Observer observer(String turnId){return new ModelInferenceProxy.Observer(){
        public boolean continueAfterDisconnect(){return true;}
        final byte[] line=new byte[65536];int length;boolean overflow,truncated;final StringBuilder text=new StringBuilder();
        public void bytes(byte[] value,int count){for(int i=0;i<count;i++){if(value[i]=='\n'){if(overflow)truncated=true;else event(new String(line,0,length,StandardCharsets.UTF_8).trim());length=0;overflow=false;}else if(length<line.length)line[length++]=value[i];else overflow=true;}}
        void event(String value){if(!value.startsWith("data:"))return;try{var event=json.readTree(value.substring(5).trim());String delta=event.path("choices").path(0).path("delta").path("content").asText("");if("content_block_delta".equals(event.path("type").asText())&&"text_delta".equals(event.path("delta").path("type").asText()))delta=event.path("delta").path("text").asText("");if(text.length()+delta.length()<=ModelDefaults.MAX_REQUEST_BYTES)text.append(delta);else truncated=true;}catch(Exception ignored){}}
        public void complete(String state,Runnable commitLedger){tx.executeWithoutResult(status->{commitLedger.run();finished(state);});}
        public void finished(String state){db.update("update mobile_chat_turns set reply_text=?,state=? where id=? and state='PREPARED'",text.toString(),"SUCCEEDED".equals(state)&&(text.toString().isBlank()||truncated)?"FAILED":state,turnId);}
    };}
}
