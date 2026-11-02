package com.funnywork;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class Json {
    private final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize internal value",e); }
    }
    public String canonical(Object value) {
        try {return mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .writeValueAsString(mapper.convertValue(value,Object.class));}
        catch(Exception e){throw new IllegalStateException("Cannot canonicalize internal value",e);}
    }
    public Set<String> strings(String value) {
        try { return mapper.readValue(value,new TypeReference<Set<String>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored scope",e); }
    }
    public Object read(String value) {
        try { return mapper.readValue(value,Object.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored JSON",e); }
    }
}
