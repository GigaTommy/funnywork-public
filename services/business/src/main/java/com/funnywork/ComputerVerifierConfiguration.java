package com.funnywork;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Startup-only deployment trust file. Secrets are environment references, never task fields. */
@Component
public class ComputerVerifierConfiguration {
    private final List<ComputerVerifiers.Binding> bindings;
    public ComputerVerifierConfiguration(@Value("${funnywork.computer-verifier-config:}") String file){
        bindings=load(file,System::getenv);
    }
    public List<ComputerVerifiers.Binding> bindings(){return bindings;}
    record Document(List<Entry> sources) {}
    record Entry(String id,List<String> tenants,List<String> zones,String baseUrl,String credentialEnv,Integer timeoutMs) {}
    static List<ComputerVerifiers.Binding> load(String file,Function<String,String> environment){
        if(file.isBlank())return List.of();
        try {
            Path path=Path.of(file);
            if(!path.isAbsolute() || !Files.isRegularFile(path))throw new IllegalArgumentException();
            byte[] bytes;try(var stream=Files.newInputStream(path)){bytes=stream.readNBytes(65537);}
            if(bytes.length>65536)throw new IllegalArgumentException();
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var doc=mapper.readValue(bytes,Document.class);
            if(doc==null || doc.sources()==null || doc.sources().isEmpty() || doc.sources().size()>32)throw new IllegalArgumentException();
            var result=new ArrayList<ComputerVerifiers.Binding>();var ids=new HashSet<String>();
            for(var entry:doc.sources()){
                if(entry==null || !ids.add(entry.id()) || entry.timeoutMs()==null || entry.baseUrl()==null
                    || entry.credentialEnv()==null || !entry.credentialEnv().matches("FUNNYWORK_SOURCE_[A-Z0-9_]{1,80}"))throw new IllegalArgumentException();
                Set<String> tenants=scope(entry.tenants()),zones=scope(entry.zones());
                token(environment.apply(entry.credentialEnv()));
                var verifier=new HttpComputerVerifier(URI.create(entry.baseUrl()),()->token(environment.apply(entry.credentialEnv())),mapper,entry.timeoutMs());
                result.add(new ComputerVerifiers.Binding(entry.id(),tenants,zones,verifier));
            }
            return List.copyOf(result);
        } catch(Exception invalid){
            throw new IllegalArgumentException("Invalid desktop verifier deployment configuration");
        }
    }
    private static Set<String> scope(List<String> values){
        if(values==null || values.isEmpty() || values.size()>64)throw new IllegalArgumentException();
        var result=new HashSet<String>();
        for(String value:values)if(value==null || !value.matches("[a-zA-Z0-9_-]{1,64}") || !result.add(value))throw new IllegalArgumentException();
        return Set.copyOf(result);
    }
    private static String token(String value){
        if(value==null || value.isBlank() || value.length()>8192 || value.chars().anyMatch(c->c<33 || c>126))throw new IllegalArgumentException("Source credential unavailable");
        return value;
    }
}
