package org.dromara.web.directory;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.net.URI;
import java.util.*;

/** Optional deployment-owned configuration. No remote-supplied endpoint is trusted. */
@Component
public class DirectoryRegistry {
    public record Source(String id, String tenantId, String authenticationUrl, String usersUrl,
                         String credentialRef, Map<String,Long> departments) {}
    record Configuration(List<Source> sources) {}
    private final Map<String,Source> sources;
    private final Environment environment;
    public DirectoryRegistry(@Value("${FUNNYWORK_IDENTITY_DIRECTORY_FILE:}") String filename, Environment environment) {
        this.environment=environment;
        this.sources=load(filename);
    }
    static Map<String,Source> load(String filename) {
        if(filename.isBlank())return Map.of();
        try {
            Path path=Path.of(filename);
            if(!path.isAbsolute()||!Files.isRegularFile(path))throw new IllegalArgumentException();
            byte[] bytes;
            try(var stream=Files.newInputStream(path)){bytes=stream.readNBytes(65537);}
            if(bytes.length>65536)throw new IllegalArgumentException();
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var document=mapper.readValue(bytes,Configuration.class);
            if(document.sources()==null||document.sources().size()>32)throw new IllegalArgumentException();
            Map<String,Source> result=new HashMap<>();
            for(var source:document.sources()) {
                if(source==null||source.id()==null||!source.id().matches("[a-z0-9-]{1,64}")||source.tenantId()==null||!source.tenantId().matches("[A-Za-z0-9_-]{1,20}")
                    ||source.credentialRef()==null||!source.credentialRef().matches("FUNNYWORK_DIRECTORY_[A-Z0-9_]{1,80}")||source.departments()==null||source.departments().isEmpty()
                    ||source.departments().size()>1000||source.departments().entrySet().stream().anyMatch(e->e.getKey().isBlank()||e.getKey().length()>128||e.getValue()==null||e.getValue()<=0))throw new IllegalArgumentException();
                endpoint(source.authenticationUrl());endpoint(source.usersUrl());
                var copy=new Source(source.id(),source.tenantId(),source.authenticationUrl(),source.usersUrl(),source.credentialRef(),Map.copyOf(source.departments()));
                if(result.putIfAbsent(source.tenantId()+":"+source.id(),copy)!=null)throw new IllegalArgumentException();
            }
            return Map.copyOf(result);
        } catch(Exception invalid){throw new IllegalArgumentException("Invalid identity directory configuration");}
    }
    static URI endpoint(String value) {
        URI uri=URI.create(value);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPort()>65535)throw new IllegalArgumentException();
        return uri;
    }
    public Source source(String tenant,String id) {
        var source=sources.get(tenant+":"+id);
        if(source==null)throw new org.dromara.common.core.exception.ServiceException("身份源未配置或已停用");
        return source;
    }
    public List<Source> sources(String tenant){return sources.values().stream().filter(s->s.tenantId().equals(tenant)).sorted(Comparator.comparing(Source::id)).toList();}
    public DirectoryAdapter adapter(Source source){return new HttpDirectoryAdapter(source,environment);}
}
