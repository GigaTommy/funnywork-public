package com.funnywork;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded 2025-11-25 Streamable HTTP client. No server-initiated execution or reconnect replay. */
final class McpHttpClient implements AutoCloseable {
    static final String PROTOCOL="2025-11-25";
    private final URI endpoint;private final String token;private final ObjectMapper mapper;private String session;
    McpHttpClient(URI endpoint,String token,ObjectMapper mapper){this.endpoint=endpoint;this.token=token;this.mapper=mapper;}
    void initialize()throws IOException{
        var result=request(1,"initialize",Map.of("protocolVersion",PROTOCOL,"capabilities",Map.of(),"clientInfo",Map.of("name","funnywork-server","version","0.1.0")));
        if(!PROTOCOL.equals(result.path("protocolVersion").asText()) || !result.path("capabilities").path("tools").isObject())throw new IOException("Unsupported MCP negotiation");
        request(null,"notifications/initialized",null);
    }
    JsonNode tools()throws IOException {var value=request(2,"tools/list",Map.of());if(!value.path("tools").isArray() || value.path("tools").size()>100 || value.has("nextCursor"))throw new IOException("Unsupported MCP catalog page");return value.path("tools");}
    JsonNode call(String tool,Map<String,Object> arguments)throws IOException{return request(3,"tools/call",Map.of("name",tool,"arguments",arguments));}
    private JsonNode decode(byte[] bytes)throws IOException{return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);}
    private JsonNode response(JsonNode value,int id)throws IOException{
        if(value==null || !value.isObject() || !"2.0".equals(value.path("jsonrpc").asText()) || !value.path("id").isIntegralNumber() || value.path("id").longValue()!=id || value.has("error") || !value.path("result").isObject())throw new IOException("Invalid MCP response");return value.path("result");
    }
    private JsonNode request(Integer id,String method,Map<String,Object> params)throws IOException{
        var message=new LinkedHashMap<String,Object>();message.put("jsonrpc","2.0");if(id!=null)message.put("id",id);message.put("method",method);if(params!=null)message.put("params",params);
        byte[] body=mapper.writeValueAsBytes(message);if(body.length>65536)throw new IOException("MCP input exceeds budget");
        var connection=(HttpURLConnection)endpoint.toURL().openConnection(Proxy.NO_PROXY);connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);connection.setUseCaches(false);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Accept","application/json, text/event-stream");connection.setRequestProperty("MCP-Protocol-Version",PROTOCOL);if(session!=null)connection.setRequestProperty("MCP-Session-Id",session);if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);
        try{try(var out=connection.getOutputStream()){out.write(body);}int status=connection.getResponseCode();
            if(id==null){if(status!=202)throw new IOException("MCP notification rejected");return null;}
            if(status!=200)throw new IOException("MCP HTTP rejected");
            if(method.equals("initialize")){String assigned=connection.getHeaderField("MCP-Session-Id");if(assigned!=null){if(assigned.isBlank() || assigned.length()>256 || assigned.chars().anyMatch(c->c<33 || c>126))throw new IOException("Invalid MCP session");session=assigned;}}
            String type=connection.getContentType();if(type==null)throw new IOException("Missing MCP content type");
            try(var input=connection.getInputStream()){
                if(type.split(";",2)[0].trim().equalsIgnoreCase("application/json")){byte[] bytes=input.readNBytes(65537);if(bytes.length>65536)throw new IOException("MCP output exceeds budget");return response(decode(bytes),id);}
                if(!type.split(";",2)[0].trim().equalsIgnoreCase("text/event-stream"))throw new IOException("Unsupported MCP content type");
                // Bound the complete stream and return as soon as the matching response arrives.
                long deadline=System.nanoTime()+5_000_000_000L;int count=0,events=0;var line=new ByteArrayOutputStream();var data=new StringBuilder();
                for(int next;(next=input.read())!=-1;){if(++count>65536 || System.nanoTime()>deadline)throw new IOException("MCP stream exceeds budget");
                    if(next!='\n'){line.write(next);continue;}String value=line.toString(StandardCharsets.UTF_8).replaceFirst("\\r$","");line.reset();
                    if(value.isEmpty()){
                        if(data.length()>0){if(++events>100)throw new IOException("Too many MCP events");JsonNode event=decode(data.toString().getBytes(StandardCharsets.UTF_8));data.setLength(0);
                            if(event.has("id"))return response(event,id);if(!event.isObject() || !"2.0".equals(event.path("jsonrpc").asText()) || !event.path("method").asText().startsWith("notifications/"))throw new IOException("Unsupported MCP server request");}
                    }else if(value.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(value.substring(5).replaceFirst("^ ",""));}
                }throw new IOException("MCP response missing");
            }
        }finally{connection.disconnect();}
    }
    @Override public void close(){if(session==null)return;HttpURLConnection connection=null;try{connection=(HttpURLConnection)endpoint.toURL().openConnection(Proxy.NO_PROXY);connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(1000);connection.setReadTimeout(1000);connection.setRequestMethod("DELETE");connection.setRequestProperty("MCP-Session-Id",session);connection.setRequestProperty("MCP-Protocol-Version",PROTOCOL);if(token!=null)connection.setRequestProperty("Authorization","Bearer "+token);connection.getResponseCode();}catch(Exception ignored){}finally{if(connection!=null)connection.disconnect();}}
}
