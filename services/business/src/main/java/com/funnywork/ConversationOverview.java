package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;

/** Visible list excerpts, bounded by Unicode code points and excluding hidden message parts. */
public final class ConversationOverview {
    public record Preview(String text,boolean attachment) {}
    private ConversationOverview() {}
    public static Preview preview(JsonNode event) {
        String type=event.path("type").asText();
        if(!type.equals("user/message")&&!type.equals("assistant/message"))return null;
        JsonNode message=event.path("data");
        if(message.path("message").isObject())message=message.path("message");
        if(type.equals("user/message")&&message.has("source")&&!"user".equals(message.path("source").path("kind").asText()))return null;
        StringBuilder text=new StringBuilder();boolean attachment=false;
        for(var part:message.path("content")) {
            String kind=part.path("type").asText();
            if((kind.isEmpty()||kind.equals("text"))&&part.path("text").isTextual())append(text,part.path("text").asText());
            if(kind.equals("file")){
                attachment=true;JsonNode reference=part.path("attachment");
                String name=reference.path("name").asText();
                if(name.isEmpty())name=reference.path("metadata").path("attachment").path("name").asText();
                append(text,name);
            }
        }
        String value=text.toString().strip();
        return value.isEmpty()&&!attachment?null:new Preview(value,attachment);
    }
    private static void append(StringBuilder target,String value) {
        int count=target.codePointCount(0,target.length());
        if(count>=400)return;
        if(!target.isEmpty()&&target.charAt(target.length()-1)!=' ') {target.append(' ');count++;}
        for(int offset=0;offset<value.length()&&count<400;) {
            int point=value.codePointAt(offset);offset+=Character.charCount(point);
            if(Character.isWhitespace(point)||Character.isSpaceChar(point)||Character.isISOControl(point)){
                if(!target.isEmpty()&&target.charAt(target.length()-1)!=' '){target.append(' ');count++;}
            }else{target.appendCodePoint(point);count++;}
        }
    }
}
