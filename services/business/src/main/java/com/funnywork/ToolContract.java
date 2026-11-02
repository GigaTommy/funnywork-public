package com.funnywork;

import java.util.*;

/** Closed, flat typed schemas for the first connector contract; not a general JSON Schema engine. */
public record ToolContract(String name,Effect effect,Map<String,Field> fields,String credentialRef,String verifier) {
    public enum Effect { READ_ONLY, ARTIFACT_WRITE, IDEMPOTENT_WRITE, NON_IDEMPOTENT_WRITE }
    public enum Kind { STRING, INTEGER, BOOLEAN }
    public record Field(Kind kind,boolean required,int maxLength) {
        public Field {Objects.requireNonNull(kind);if(maxLength<1 || maxLength>10000)throw new IllegalArgumentException("Invalid field bound");}
    }
    public ToolContract {
        if(name==null || !name.matches("[a-z][a-z0-9._-]{1,119}"))throw new IllegalArgumentException("Invalid tool name");
        Objects.requireNonNull(effect);fields=Map.copyOf(fields);
        if(fields.size()>32 || fields.keySet().stream().anyMatch(k->!k.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")))throw new IllegalArgumentException("Invalid schema");
        if(credentialRef==null || !credentialRef.matches("vault://[a-zA-Z0-9/_-]{1,160}"))throw new IllegalArgumentException("Credential must be a reference");
        if(verifier==null || !verifier.matches("[a-z][a-z0-9._-]{1,119}"))throw new IllegalArgumentException("Verifier is required");
    }
    public Map<String,Object> validate(Map<String,Object> input) {
        if(input==null || !fields.keySet().containsAll(input.keySet()))throw new ApiException(400,"TOOL_INPUT_INVALID","Unknown input field");
        Map<String,Object> canonical=new TreeMap<>(input);
        for(var entry:fields.entrySet()) {
            Object value=input.get(entry.getKey());Field field=entry.getValue();
            if(value==null) {
                if(field.required() || input.containsKey(entry.getKey()))throw new ApiException(400,"TOOL_INPUT_INVALID","Missing or null input field");
                continue;
            }
            boolean valid=switch(field.kind()) {
                case STRING -> value instanceof String s && s.codePointCount(0,s.length())<=field.maxLength();
                case INTEGER -> isInteger(value);
                case BOOLEAN -> value instanceof Boolean;
            };
            if(!valid)throw new ApiException(400,"TOOL_INPUT_INVALID","Input type or size mismatch");
            if(field.kind()==Kind.INTEGER)canonical.put(entry.getKey(),new java.math.BigDecimal(value.toString()).longValueExact());
        }
        return Collections.unmodifiableMap(canonical);
    }
    private static boolean isInteger(Object value) {
        if(!(value instanceof Number))return false;
        try {new java.math.BigDecimal(value.toString()).longValueExact();return true;}
        catch(NumberFormatException | ArithmeticException e) {return false;}
    }
    public Map<String,Object> inputSchema() {
        Map<String,Object> properties=new TreeMap<>();List<String> required=new ArrayList<>();
        for(var entry:new TreeMap<>(fields).entrySet()) {
            Field field=entry.getValue();Map<String,Object> schema=new LinkedHashMap<>();
            schema.put("type",switch(field.kind()){case STRING->"string";case INTEGER->"integer";case BOOLEAN->"boolean";});
            if(field.kind()==Kind.STRING)schema.put("maxLength",field.maxLength());
            if(field.kind()==Kind.INTEGER){schema.put("minimum",Long.MIN_VALUE);schema.put("maximum",Long.MAX_VALUE);}
            properties.put(entry.getKey(),schema);if(field.required())required.add(entry.getKey());
        }
        return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
    }
    public boolean permitsUnverifiedRetry() {return effect==Effect.READ_ONLY;}
}
