package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain chain(HttpSecurity http,AuthService auth,EnterpriseIdentity enterprise,NodeCertificates certificates,ObjectMapper mapper,RecoveryGate recoveryGate,ManagementIdentity management,DesktopPolicies desktopPolicies,ModelMiddleware middleware) throws Exception {
        var filter=new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                if(req.getRequestURI().startsWith("/api/account/"))res.setHeader("Cache-Control","no-store");
                if(req.getRequestURI().startsWith("/api/v1/management-history"))res.setHeader("Cache-Control","no-store");
                // Recovery quarantine is checked before any restored credential or API is used.
                // Health also returns 503 so traffic managers cannot mark this instance ready.
                String recoveryFailure=recoveryGate.failure();
                if(recoveryFailure!=null) {
                    res.setStatus(503);res.setContentType("application/json");
                    res.setHeader("Cache-Control","no-store");
                    mapper.writeValue(res.getOutputStream(),Map.of("code",recoveryFailure));return;
                }
                // Management has its own Sa-Token authentication. Never reinterpret its JWT
                // as an Agent credential; the fixed upstream validates each forwarded request.
                if (req.getRequestURI().startsWith("/ruoyi/")) { chain.doFilter(req,res); return; }
                String header=req.getHeader("Authorization");
                if(header!=null) {
                    try {
                        if(!header.startsWith("Bearer ") || header.length()>16391) throw new ApiException(401,"UNAUTHENTICATED","Invalid credential");
                        String token=header.substring(7);
                        Identity identity=token.startsWith("mwi.")?middleware.authenticateEntry(token,req.getRequestURI()):token.startsWith("ruoyi.")?management.authenticate(token.substring(6),req.getMethod(),req.getRequestURI()):"1".equals(req.getHeader("X-FunnyWorker-Management"))?management.authenticate(token,req.getMethod(),req.getRequestURI()):(token.contains(".")?enterprise.authenticate(token):auth.authenticate(token));
                        // Set only after the identity platform has checked the dedicated content permission.
                        if(!token.startsWith("mwi.")&&(token.startsWith("ruoyi.")||"1".equals(req.getHeader("X-FunnyWorker-Management")))&&(req.getRequestURI().equals("/api/v1/management-history")||req.getRequestURI().matches("/api/v1/management-history/(DESKTOP|EMPLOYEE|MOBILE)/[A-Za-z0-9_-]{1,64}/content")))req.setAttribute("funnywork.management-validated",true);
                        certificates.verify(identity,req);
                        desktopPolicies.checkRequest(identity,req.getMethod(),req.getRequestURI());
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of()));
                    } catch(org.springframework.dao.DataAccessResourceFailureException | org.springframework.dao.TransientDataAccessResourceException | org.springframework.transaction.CannotCreateTransactionException e) {
                        SecurityContextHolder.clearContext();
                        res.setStatus(503);res.setContentType("application/json");
                        mapper.writeValue(res.getOutputStream(),Map.of("code","STORAGE_UNAVAILABLE"));return;
                    } catch(ApiException e) {
                        res.setStatus(e.status);res.setContentType("application/json");
                        mapper.writeValue(res.getOutputStream(),Map.of("code",e.code,"message",e.getMessage()));return;
                    }
                }
                // Reject missing credentials before buffering potentially large inference bodies.
                boolean publicApi="POST".equals(req.getMethod()) && Set.of("/api/account/login","/api/v1/node-enrollments/redeem").contains(req.getRequestURI());
                if(req.getRequestURI().startsWith("/api/") && !publicApi && SecurityContextHolder.getContext().getAuthentication()==null) {
                    res.setStatus(401);res.setContentType("application/json");
                    mapper.writeValue(res.getOutputStream(),Map.of("code","UNAUTHENTICATED"));return;
                }
                // Larger envelopes are restricted to explicit inference, artifact and device-state routes.
                boolean artifactUpload="POST".equals(req.getMethod()) && (req.getRequestURI().equals("/api/v1/artifacts") || req.getRequestURI().matches("/api/v1/artifacts/[A-Za-z0-9_-]{1,64}/versions"));
                // State combines independently bounded sessions, workspaces and model choices.
                boolean deviceState="POST".equals(req.getMethod()) && req.getRequestURI().matches("/api/account/sync/devices/[A-Za-z0-9_-]{1,64}/state");
                boolean inferenceExecute="POST".equals(req.getMethod()) && req.getRequestURI().matches("/api/account/inference/tickets/[A-Za-z0-9_-]{1,64}/execute");
                int payloadLimit=inferenceExecute?ModelDefaults.MAX_REQUEST_BYTES:artifactUpload?3_145_728:deviceState?2_200_000:1_048_576;
                if(req.getContentLengthLong()>payloadLimit) {
                    res.setStatus(413);res.setContentType("application/json");
                    mapper.writeValue(res.getOutputStream(),Map.of("code","PAYLOAD_TOO_LARGE"));return;
                }
                if(Set.of("POST","PUT","PATCH").contains(req.getMethod())) {
                    byte[] body=req.getInputStream().readNBytes(payloadLimit+1);
                    if(body.length>payloadLimit) {
                        res.setStatus(413);res.setContentType("application/json");
                        mapper.writeValue(res.getOutputStream(),Map.of("code","PAYLOAD_TOO_LARGE"));return;
                    }
                    var wrapped=new HttpServletRequestWrapper(req) {
                        @Override public ServletInputStream getInputStream() {
                            var bytes=new ByteArrayInputStream(body);
                            return new ServletInputStream() {
                                @Override public int read(){return bytes.read();}
                                @Override public int read(byte[] b,int off,int len){return bytes.read(b,off,len);}
                                @Override public boolean isFinished(){return bytes.available()==0;}
                                @Override public boolean isReady(){return true;}
                                @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous JSON API");}
                            };
                        }
                        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
                    };
                    chain.doFilter(wrapped,res);
                } else chain.doFilter(req,res);
            }
        };
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Preserve the original 404/500 during the container's internal error dispatch.
            // Direct requests, including requests to /error, still require authentication.
            .authorizeHttpRequests(a->a.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Container-owned async continuations retain the original admission authorization.
                .requestMatchers(req->(req.getDispatcherType()==DispatcherType.ASYNC ||
                    (req.getDispatcherType()==DispatcherType.INCLUDE && "/error".equals(req.getAttribute("jakarta.servlet.include.request_uri")) && req.getAttribute("jakarta.servlet.error.request_uri")!=null)) &&
                    ((req.getDispatcherType()==DispatcherType.INCLUDE?String.valueOf(req.getAttribute("jakarta.servlet.error.request_uri")):req.getRequestURI()).matches("/api/account/chats/[A-Za-z0-9_-]{1,64}/turns/[A-Za-z0-9_-]{1,64}/execute") ||
                     (req.getDispatcherType()==DispatcherType.INCLUDE?String.valueOf(req.getAttribute("jakarta.servlet.error.request_uri")):req.getRequestURI()).matches("/api/account/inference/tickets/[A-Za-z0-9_-]{1,64}/execute"))).permitAll()
                .requestMatchers("/actuator/health").permitAll().requestMatchers(org.springframework.http.HttpMethod.GET,"/admin/**").permitAll().requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/node-enrollments/redeem","/api/account/login").permitAll().requestMatchers("/ruoyi/**").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{
                res.setStatus(401);res.setContentType("application/json");mapper.writeValue(res.getOutputStream(),Map.of("code","UNAUTHENTICATED"));
            }))
            .addFilterBefore(filter,UsernamePasswordAuthenticationFilter.class).build();
    }
}
