package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Observes copies of protocol events; never rewrites the stream or persists model content. */
public final class ModelUsage {
    private final ObjectMapper mapper;
    private final String protocol;
    private final java.time.Clock clock;
    private Long firstOutputAt;
    private final ObjectNode raw;
    private final byte[] line=new byte[65536];
    private int length;
    private boolean overflow;
    private boolean complete;
    private boolean usageOverflow;
    private boolean outputUsageObserved;
    public ModelUsage(ObjectMapper mapper,String protocol){this(mapper,protocol,java.time.Clock.systemUTC());}
    ModelUsage(ObjectMapper mapper,String protocol,java.time.Clock clock){this.mapper=mapper;this.protocol=protocol;this.clock=clock;raw=mapper.createObjectNode();}
    public Long firstOutputAt(){return firstOutputAt;}
    public void bytes(byte[] bytes,int count){for(int i=0;i<count;i++){
        if(bytes[i]=='\n'){if(!overflow)event(new String(line,0,length,StandardCharsets.UTF_8).trim());else usageOverflow=true;length=0;overflow=false;}
        else if(length<line.length)line[length++]=bytes[i];else overflow=true;
    }}
    public void event(String line){
        if(!line.startsWith("data:"))return;String data=line.substring(5).trim();
        if("[DONE]".equals(data)){if("CHAT_COMPLETIONS".equals(protocol))complete=true;return;}
        try{JsonNode value=mapper.readTree(data);observe(value);if(firstOutputAt==null&&hasOutput(value))firstOutputAt=clock.millis();}catch(Exception ignored){/* Invalid or extended events still pass through byte-for-byte. */}
    }
    public void response(byte[] bytes){try{JsonNode value=mapper.readTree(bytes);observe(value);if(value!=null&&"message".equals(value.path("type").asText())&&value.path("usage").has("output_tokens"))outputUsageObserved=true;complete=value!=null&&(value.path("choices").isArray()||("message".equals(value.path("type").asText())&&value.path("content").isArray()&&!value.path("stop_reason").isNull()));}catch(Exception ignored){}}
    private boolean hasOutput(JsonNode value){
        if(value==null)return false;
        if("CHAT_COMPLETIONS".equals(protocol)){
            for(JsonNode choice:value.path("choices")){
                JsonNode delta=choice.path("delta");
                if(nonempty(delta.path("content"))||nonempty(delta.path("reasoning_content"))||nonempty(delta.path("reasoning")))return true;
                for(JsonNode tool:delta.path("tool_calls"))if(nonempty(tool.path("function").path("arguments"))||nonempty(tool.path("function").path("name")))return true;
            }
        }else if("content_block_delta".equals(value.path("type").asText())){
            JsonNode delta=value.path("delta");return nonempty(delta.path("text"))||nonempty(delta.path("thinking"))||nonempty(delta.path("partial_json"));
        }
        return false;
    }
    private boolean nonempty(JsonNode value){return value.isTextual()&&!value.textValue().isEmpty();}
    private void observe(JsonNode value){
        if(value==null)return;
        if("message_stop".equals(value.path("type").asText()))complete=true;
        JsonNode usage=value.get("usage");if("message_start".equals(value.path("type").asText()))usage=value.path("message").get("usage");
        if(usage!=null&&usage.isObject()){
            if("message_delta".equals(value.path("type").asText())&&usage.has("output_tokens"))outputUsageObserved=true;
            // Partial Messages usage updates merge; cumulative samples replace, never sum.
            for(String key:Arrays.asList("input_tokens","output_tokens","prompt_tokens","completion_tokens","cache_read_input_tokens","cache_creation_input_tokens","prompt_tokens_details","prompt_cache_hit_tokens","prompt_cache_miss_tokens"))
                if(usage.has(key))raw.set(key,usage.get(key));
        }
    }
    private Long token(JsonNode node){return node!=null&&node.isIntegralNumber()&&node.canConvertToLong()&&node.longValue()>=0?node.longValue():null;}
    public Long input(){
        if("CHAT_COMPLETIONS".equals(protocol))return token(raw.get("prompt_tokens"));
        Long base=token(raw.get("input_tokens"));if(base==null)return null;
        try{return Math.addExact(Math.addExact(base,cache("cache_read_input_tokens")),cache("cache_creation_input_tokens"));}catch(ArithmeticException e){return null;}
    }
    private long cache(String key){Long n=token(raw.get(key));return n==null?0:n;}
    public Long output(){if("MESSAGES".equals(protocol)&&!outputUsageObserved)return null;return token(raw.get("CHAT_COMPLETIONS".equals(protocol)?"completion_tokens":"output_tokens"));}
    public Long cached(){return "CHAT_COMPLETIONS".equals(protocol)?chatCached():token(raw.get("cache_read_input_tokens"));}
    private Long chatCached(){
        Long standard=token(raw.path("prompt_tokens_details").get("cached_tokens"));
        return standard!=null?standard:token(raw.get("prompt_cache_hit_tokens"));
    }
    private boolean validChatCache(){
        JsonNode details=raw.path("prompt_tokens_details");
        if(details.has("cached_tokens")&&token(details.get("cached_tokens"))==null)return false;
        for(String key:Arrays.asList("prompt_cache_hit_tokens","prompt_cache_miss_tokens"))if(raw.has(key)&&token(raw.get(key))==null)return false;
        Long standard=token(details.get("cached_tokens")),hit=token(raw.get("prompt_cache_hit_tokens")),miss=token(raw.get("prompt_cache_miss_tokens")),input=input();
        if(standard!=null&&hit!=null&&!standard.equals(hit))return false;
        Long read=standard!=null?standard:hit;
        if(input!=null&&miss!=null){
            if(miss>input)return false;
            if(read!=null&&read!=input-miss)return false;
        }
        return true;
    }
    public boolean complete(){return complete;}
    public String raw(){return raw.isEmpty()?null:raw.toString();}
    public String quality(){boolean valid=true;for(String key:Arrays.asList("input_tokens","output_tokens","prompt_tokens","completion_tokens","cache_read_input_tokens","cache_creation_input_tokens"))if(raw.has(key)&&token(raw.get(key))==null)valid=false;
        if("CHAT_COMPLETIONS".equals(protocol)&&!validChatCache())valid=false;
        Long cached=cached(),input=input();if(cached!=null&&input!=null&&cached>input)valid=false;
        return complete&&valid&&!usageOverflow&&input()!=null&&output()!=null?"COMPLETE":raw.isEmpty()?"UNKNOWN":"PARTIAL";}
}
