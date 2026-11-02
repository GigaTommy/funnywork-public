package com.funnywork;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;

/** Same-origin management gateway. The destination is fixed and never supplied by a caller. */
@RestController
public class ManagementProxyController {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> FORWARDED=Set.of("authorization","clientid","content-type","content-language","encrypt-key");
    @RequestMapping("/ruoyi/**")
    public void proxy(HttpServletRequest request,HttpServletResponse response) throws IOException {
        String path=request.getRequestURI().substring("/ruoyi".length());
        if(path.contains("..") || path.contains("\\") || path.contains("%")) {response.sendError(400);return;}
        byte[] body=request.getInputStream().readNBytes(3_145_729);
        if(body.length>3_145_728){response.sendError(413);return;}
        String query=request.getQueryString();
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8767"+path+(query==null?"":"?"+query)))
            .timeout(Duration.ofSeconds(30)).method(request.getMethod(),HttpRequest.BodyPublishers.ofByteArray(body));
        for(String name:FORWARDED) {String value=request.getHeader(name);if(value!=null)builder.header(name,value);}
        try {
            var upstream=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            response.setStatus(upstream.statusCode());response.setHeader("Cache-Control","no-store");
            for(String name:Set.of("content-type","content-disposition","encrypt-key"))
                upstream.headers().firstValue(name).ifPresent(value->response.setHeader(name,value));
            try(var input=upstream.body()){input.transferTo(response.getOutputStream());}
        } catch(InterruptedException e) {Thread.currentThread().interrupt();response.sendError(503);}
          catch(IOException e) {response.setStatus(503);response.setContentType("application/json");response.getWriter().write("{\"code\":503,\"msg\":\"管理服务暂时不可用\"}");}
    }
}
