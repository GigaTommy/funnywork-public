package com.funnywork;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ContentDisposition;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Account-authenticated result directory and bounded binary downloads. */
@RestController
@RequestMapping("/api/account/sync/sessions/{session}/results")
public class DesktopResultsController {
    private final DesktopResults results;private final ResultBlobReader blobs;
    public DesktopResultsController(DesktopResults results,ResultBlobReader blobs){this.results=results;this.blobs=blobs;}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal Identity actor,@PathVariable String session,@RequestParam(defaultValue="-1") long after){return results.list(actor,session,after);}
    @GetMapping("/{id}/versions") public List<Map<String,Object>> versions(@AuthenticationPrincipal Identity actor,@PathVariable String session,@PathVariable String id){return results.versions(actor,session,id);}
    @GetMapping("/{id}/versions/{version}/files/{key}")
    public void download(@AuthenticationPrincipal Identity actor,@PathVariable String session,@PathVariable String id,@PathVariable int version,@PathVariable String key,HttpServletRequest request,HttpServletResponse response)throws IOException{
        var file=results.contentReference(actor,session,id,version,key);String hash=file.path("attachmentId").asText().substring(7);long size=file.path("bytes").asLong();
        if(!blobs.verified(actor,hash,size))throw new ApiException(409,"RESULT_NOT_SYNCED","File has not finished synchronizing");
        String etag="\""+hash+"\"";response.setHeader("ETag",etag);response.setHeader("Accept-Ranges","bytes");response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        if(etag.equals(request.getHeader("If-None-Match"))){response.setStatus(304);return;}
        long first=0,last=size-1;String range=request.getHeader("Range"),ifRange=request.getHeader("If-Range");
        if(range!=null&&(ifRange==null||etag.equals(ifRange))){
            var match=Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
            try{
                if(!match.matches()||size==0||match.group(1).isEmpty()&&match.group(2).isEmpty())throw new IllegalArgumentException();
                if(match.group(1).isEmpty()){long suffix=Long.parseLong(match.group(2));if(suffix<=0)throw new IllegalArgumentException();first=Math.max(0,size-suffix);}
                else{first=Long.parseLong(match.group(1));if(!match.group(2).isEmpty())last=Math.min(last,Long.parseLong(match.group(2)));}
                if(first>last||first>=size)throw new IllegalArgumentException();
            }catch(IllegalArgumentException error){response.setStatus(416);response.setHeader("Content-Range","bytes */"+size);return;}
            response.setStatus(206);response.setHeader("Content-Range","bytes "+first+"-"+last+"/"+size);
        }
        response.setContentType("application/octet-stream");response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(file.path("name").asText(),StandardCharsets.UTF_8).build().toString());response.setContentLengthLong(Math.max(0,last-first+1));
        blobs.write(actor,hash,first,last,response.getOutputStream());
    }
}
