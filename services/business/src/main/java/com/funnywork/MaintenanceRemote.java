package com.funnywork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Fixed GitHub operations only. Candidate URLs never choose hosts, repositories or credentials. */
@Service
public class MaintenanceRemote {
    private final Environment env;private final ObjectMapper mapper;private final MaintenanceGithubCredentials credentials;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MaintenanceRemote(Environment env,ObjectMapper mapper,MaintenanceGithubCredentials credentials){this.env=env;this.mapper=mapper;this.credentials=credentials;}
    public JsonNode request(String slug,String ref,String method,String path,Object body) throws Exception {
        boolean immutableComparison="GET".equals(method)&&body==null&&path.matches("/compare/[a-f0-9]{40}\\.\\.\\.[a-f0-9]{40}");
        if(!slug.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||!ref.matches("FUNNYWORK_GITHUB_[A-Z0-9_]{1,80}")||(!path.isEmpty()&&!path.startsWith("/"))||(path.contains("..")&&!immutableComparison))throw ApiException.forbidden();
        String token=credentials.token(ref,slug);
        return send("/repos/"+slug+path,token,method,body);
    }
    private JsonNode send(String path,String token,String method,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://api.github.com"+path)).timeout(Duration.ofSeconds(20))
            .header("Accept","application/vnd.github+json").header("Authorization","Bearer "+token)
            .header("X-GitHub-Api-Version","2022-11-28").header("User-Agent","FunnyWork-maintenance");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if(body!=null)request.header("Content-Type","application/json");
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;
        try(var stream=response.body()){bytes=stream.readNBytes(2000001);}
        if(bytes.length>2000000)throw ApiException.conflict("MAINTENANCE_GITHUB_RESPONSE_TOO_LARGE");
        if(response.statusCode()<200||response.statusCode()>299)throw ApiException.conflict("MAINTENANCE_GITHUB_HTTP_"+response.statusCode());
        return bytes.length==0?mapper.createObjectNode():mapper.readTree(bytes);
    }
    public String expectedActor(String ref){String slug=env.getProperty(ref+"_APP_SLUG");if(slug==null||!slug.matches("[a-z0-9-]+"))throw ApiException.conflict("MAINTENANCE_GITHUB_APP_ACTOR_UNAVAILABLE");return slug+"[bot]";}
    private String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    public Map<String,Object> baseline(Map<String,Object> policy,List<String> scope) throws Exception {
        String slug=(String)policy.get("slug"),ref=(String)policy.get("read_token_ref");
        String sha=request(slug,ref,"GET","/branches/"+encode((String)policy.get("base_branch")),null).path("commit").path("sha").asText();
        if(!sha.matches("[a-f0-9]{40}|[a-f0-9]{64}"))throw ApiException.conflict("MAINTENANCE_BASE_INVALID");
        var files=new ArrayList<Map<String,Object>>();var cache=new HashMap<String,JsonNode>();
        String root=request(slug,ref,"GET","/git/commits/"+sha,null).path("tree").path("sha").asText();
        for(String allowed:scope){
            JsonNode entry=entry(slug,ref,root,allowed,cache);if(entry==null)continue;
            if("blob".equals(entry.path("type").asText())){files.add(Map.of("path",allowed,"sha",entry.path("sha").asText(),"size",entry.path("size").asLong()));continue;}
            if(!"tree".equals(entry.path("type").asText()))throw ApiException.conflict("MAINTENANCE_SCOPE_TYPE_INVALID");
            var tree=request(slug,ref,"GET","/git/trees/"+entry.path("sha").asText()+"?recursive=1",null);
            if(tree.path("truncated").asBoolean())throw ApiException.conflict("MAINTENANCE_CODE_CONTEXT_TOO_LARGE");
            for(var file:tree.path("tree"))if("blob".equals(file.path("type").asText())){
                if(files.size()>=200)throw ApiException.conflict("MAINTENANCE_SCOPE_TOO_LARGE");
                files.add(Map.of("path",allowed+"/"+file.path("path").asText(),"sha",file.path("sha").asText(),"size",file.path("size").asLong()));
            }
        }
        var snippets=new ArrayList<Map<String,Object>>();int total=0;
        for(var file:files){
            String path=(String)file.get("path");long size=((Number)file.get("size")).longValue();
            if(snippets.size()>=8)break;
            if(size>12000||sensitive(path)||!path.matches(".*\\.(java|ts|tsx|vue|json|md|py|swift|kt|kts)$"))continue;
            if(total+size>48000)continue;
            var blob=request(slug,ref,"GET","/git/blobs/"+file.get("sha"),null);
            if(!"base64".equals(blob.path("encoding").asText()))throw ApiException.conflict("MAINTENANCE_SOURCE_ENCODING_INVALID");
            byte[] content=Base64.getMimeDecoder().decode(blob.path("content").asText());
            if(content.length>12000||content.length!=size)throw ApiException.conflict("MAINTENANCE_SOURCE_SIZE_CHANGED");
            snippets.add(Map.of("path",path,"sha",file.get("sha"),"content",new String(content,StandardCharsets.UTF_8)));total+=content.length;
        }
        return Map.of("baseSha",sha,"files",files,"sourceSnippets",snippets);
    }
    public Map<String,Object> candidate(Map<String,Object> policy,Map<?,?> report,List<String> scope) throws Exception {
        String slug=(String)policy.get("slug"),ref=(String)policy.get("read_token_ref"),url=String.valueOf(report.get("prUrl"));
        String prefix="https://github.com/"+slug+"/pull/";
        if(!url.startsWith(prefix)||!url.substring(prefix.length()).matches("[1-9][0-9]*"))throw ApiException.conflict("MAINTENANCE_CANDIDATE_INVALID");
        String number=url.substring(prefix.length());var pr=request(slug,ref,"GET","/pulls/"+number,null);
        if(!Objects.equals(report.get("resultSha"),pr.path("head").path("sha").asText())||!slug.equals(pr.path("head").path("repo").path("full_name").asText())
            ||!policy.get("base_branch").equals(pr.path("base").path("ref").asText())||!"open".equals(pr.path("state").asText())||pr.path("draft").asBoolean())throw ApiException.conflict("MAINTENANCE_CANDIDATE_CHANGED");
        if(pr.path("changed_files").asInt()>100)throw ApiException.conflict("MAINTENANCE_DIFF_TOO_LARGE");
        var files=request(slug,ref,"GET","/pulls/"+number+"/files?per_page=100",null);
        if(!files.isArray()||files.size()!=pr.path("changed_files").asInt()||files.isEmpty())throw ApiException.conflict("MAINTENANCE_DIFF_INCOMPLETE");
        String root=request(slug,ref,"GET","/git/commits/"+pr.path("head").path("sha").asText(),null).path("tree").path("sha").asText();
        var treeCache=new HashMap<String,JsonNode>();
        for(var file:files){
            if(!"removed".equals(file.path("status").asText())){
                var entry=entry(slug,ref,root,file.path("filename").asText(),treeCache);
                if(entry==null||!"100644".equals(entry.path("mode").asText()))throw ApiException.conflict("MAINTENANCE_CANDIDATE_FILE_TYPE_INVALID");
            }
            for(String name:List.of("filename","previous_filename"))if(file.has(name)){
                String path=file.path(name).asText();
                if(scope.stream().noneMatch(s->path.equals(s)||path.startsWith(s+"/"))||sensitive(path))throw ApiException.conflict("MAINTENANCE_SENSITIVE_OR_OUT_OF_SCOPE_CHANGE");
            }
            if(!file.hasNonNull("patch"))throw ApiException.conflict("MAINTENANCE_DIFF_INCOMPLETE");
        }
        if(files.toString().length()>100000)throw ApiException.conflict("MAINTENANCE_DIFF_TOO_LARGE");
        return Map.of("prNumber",number,"headSha",pr.path("head").path("sha").asText(),"baseSha",pr.path("base").path("sha").asText(),
            "author",pr.path("user").path("id").asLong(),"files",files,"environmentPolicy",policy.get("environment_policy"));
    }
    private JsonNode entry(String slug,String ref,String root,String path,Map<String,JsonNode> cache)throws Exception{
        if(!path.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")||path.contains(".."))throw ApiException.forbidden();
        String tree=root;String[] parts=path.split("/");
        for(int index=0;index<parts.length;index++){
            JsonNode listing=cache.get(tree);if(listing==null){listing=request(slug,ref,"GET","/git/trees/"+tree,null);cache.put(tree,listing);}
            if(listing.path("truncated").asBoolean())throw ApiException.conflict("MAINTENANCE_CODE_CONTEXT_TOO_LARGE");
            JsonNode found=null;for(var child:listing.path("tree"))if(parts[index].equals(child.path("path").asText())){found=child;break;}
            if(found==null)return null;
            if(index==parts.length-1)return found;
            if(!"tree".equals(found.path("type").asText()))throw ApiException.conflict("MAINTENANCE_CANDIDATE_FILE_TYPE_INVALID");
            tree=found.path("sha").asText();
        }
        return null;
    }
    static boolean sensitive(String path){return path.matches("(?i)(^|.*/)(AGENTS\\.md|SKILL\\.md|CODEOWNERS|.*\\.(pem|key|p12))$")
        ||path.startsWith(".github/")||path.startsWith(".local/")||path.contains("/db/migration/")||path.contains("Maintenance")||path.contains("maintenance-");}
    public String currentUser(Map<String,Object> p)throws Exception{
        String token=credentials.token((String)p.get("delivery_token_ref"),(String)p.get("slug"));
        var user=send("/user",token,"GET",null);String login=user.path("login").asText();
        String expected=env.getProperty("funnywork.maintenance-development-github-login","");
        if(expected.isBlank()||!expected.equalsIgnoreCase(login))throw ApiException.conflict("MAINTENANCE_GITHUB_ACCOUNT_MISMATCH");return login;
    }
    public void requireSingleAccountDevelopment(Map<String,Object> p)throws Exception{
        if(!env.getProperty("funnywork.maintenance-single-account-development",Boolean.class,false)||!"DEVELOPMENT_FUNCTIONAL".equals(p.get("environment_policy")))throw ApiException.forbidden();
        currentUser(p);
        var repo=request((String)p.get("slug"),(String)p.get("delivery_token_ref"),"GET","",null);
        if(!repo.path("private").asBoolean()||!repo.path("permissions").path("push").asBoolean())throw ApiException.conflict("MAINTENANCE_PRIVATE_REPOSITORY_WRITE_REQUIRED");
        // Existing GitHub rules still apply to the ordinary merge API; no admin bypass or force push.
    }
    public void requireProtected(Map<String,Object> p) throws Exception {
        String token=credentials.token((String)p.get("delivery_token_ref"),(String)p.get("slug"));
        if(token==null||token.isBlank())throw ApiException.conflict("MAINTENANCE_DELIVERY_IDENTITY_UNAVAILABLE");
        // This endpoint rejects personal access tokens: automatic delivery needs an installation identity.
        var repos=send("/installation/repositories?per_page=100",token,"GET",null);
        boolean installed=false;for(var repo:repos.path("repositories"))if(p.get("slug").equals(repo.path("full_name").asText()))installed=true;
        if(!installed)throw ApiException.conflict("MAINTENANCE_DELIVERY_INSTALLATION_REQUIRED");
        var protection=request((String)p.get("slug"),(String)p.get("read_token_ref"),"GET","/branches/"+encode((String)p.get("base_branch"))+"/protection",null);
        String app=env.getProperty(p.get("delivery_token_ref")+"_APP_ID");
        boolean allowed=false;for(var actor:protection.path("restrictions").path("apps"))if(actor.path("id").asText().equals(app))allowed=true;
        if(!allowed||protection.path("restrictions").path("users").size()!=0||protection.path("restrictions").path("teams").size()!=0)throw ApiException.conflict("MAINTENANCE_DELIVERY_ACTOR_RESTRICTION_REQUIRED");
        if(!protection.path("required_pull_request_reviews").path("dismiss_stale_reviews").asBoolean())throw ApiException.conflict("MAINTENANCE_STALE_APPROVAL_INVALIDATION_REQUIRED");
        if(!protection.path("enforce_admins").path("enabled").asBoolean()||protection.path("allow_force_pushes").path("enabled").asBoolean()
            ||protection.path("allow_deletions").path("enabled").asBoolean()||!protection.path("required_status_checks").path("strict").asBoolean()
            ||protection.path("required_pull_request_reviews").path("required_approving_review_count").asInt()<1)throw ApiException.conflict("MAINTENANCE_BRANCH_PROTECTION_REQUIRED");
    }
}
