package com.funnywork;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
public class ModelMiddlewareController {
    private final ModelMiddleware service;private final ModelInferenceProxy proxy;
    public ModelMiddlewareController(ModelMiddleware service,ModelInferenceProxy proxy){this.service=service;this.proxy=proxy;}
    public record Enabled(@NotNull Boolean enabled) {}
    public record Reconcile(@NotBlank @Size(max=1000) String evidence) {}
    public record EntryDevice(@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,128}") String deviceId) {}
    @GetMapping("/api/v1/model-middleware/pools") public Object pools(@AuthenticationPrincipal Identity actor){return Map.of("items",service.pools(actor));}
    @PostMapping("/api/v1/model-middleware/pools") public Object pool(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ModelMiddleware.Pool body){return service.savePool(actor,null,body);}
    @PutMapping("/api/v1/model-middleware/pools/{id}") public Object pool(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody ModelMiddleware.Pool body){return service.savePool(actor,id,body);}
    @GetMapping("/api/v1/model-middleware/deployments") public Object deployments(@AuthenticationPrincipal Identity actor){return Map.of("items",service.deployments(actor));}
    @PostMapping("/api/v1/model-middleware/deployments") public Object deployment(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ModelMiddleware.Deployment body){return service.createDeployment(actor,body);}
    @PutMapping("/api/v1/model-middleware/deployments/{id}/enabled") public Object enabled(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody Enabled body){service.enableDeployment(actor,id,body.enabled());return Map.of("updated",true);}
    @GetMapping("/api/v1/model-middleware/queue") public Object queue(@AuthenticationPrincipal Identity actor,@RequestParam String poolId,@RequestParam(defaultValue="100") int limit){return Map.of("items",service.queue(actor,poolId,limit));}
    @GetMapping("/api/v1/model-middleware/queue/page") public Object queuePage(@AuthenticationPrincipal Identity actor,@RequestParam String poolId,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String owner,@RequestParam(defaultValue="") String deployment,@RequestParam(defaultValue="0") long afterCreated,@RequestParam(defaultValue="") String afterId,@RequestParam(defaultValue="50") int limit){return service.queuePage(actor,poolId,state,owner,deployment,afterCreated,afterId,limit);}
    @PostMapping("/api/v1/model-middleware/tickets/{id}/cancel") public Object cancelAdmin(@AuthenticationPrincipal Identity actor,@PathVariable String id){service.cancel(actor,id,true);return Map.of("updated",true);}
    @PostMapping("/api/v1/model-middleware/tickets/{id}/reconcile") public Object reconcile(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody Reconcile body){service.reconcile(actor,id,body.evidence());return Map.of("updated",true);}
    @GetMapping("/api/v1/model-middleware/usage") public Object usage(@AuthenticationPrincipal Identity actor,@RequestParam long from,@RequestParam long until,@RequestParam(defaultValue="") String afterOwner,@RequestParam(defaultValue="") String afterDeployment,@RequestParam(defaultValue="50") int limit){return service.usage(actor,from,until,afterOwner,afterDeployment,limit);}
    @GetMapping("/api/account/inference/bootstrap") public Object bootstrap(@AuthenticationPrincipal Identity actor){return service.bootstrap(actor);}
    @PostMapping("/api/account/inference/credential") public Object credential(@AuthenticationPrincipal Identity actor,HttpServletResponse response,@Valid @RequestBody(required=false) EntryDevice body){response.setHeader("Cache-Control","no-store");return service.credential(actor,body==null?"":body.deviceId());}
    @PostMapping("/api/account/inference/credentials/revoke") public Object revoke(@AuthenticationPrincipal Identity actor,@Valid @RequestBody EntryDevice body){service.revokeEntry(actor,body.deviceId());return Map.of("updated",true);}
    @GetMapping("/api/account/inference/catalog") public Object catalog(@AuthenticationPrincipal Identity actor){return Map.of("items",service.catalog(actor));}
    @PostMapping("/api/account/inference/tickets") public Object admit(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ModelMiddleware.Ticket body){return service.admit(actor,body);}
    @GetMapping("/api/account/inference/tickets/{id}") public Object ticket(@AuthenticationPrincipal Identity actor,@PathVariable String id){return service.readTicket(actor,id);}
    @PostMapping("/api/account/inference/tickets/{id}/cancel") public Object cancel(@AuthenticationPrincipal Identity actor,@PathVariable String id){service.cancel(actor,id,false);return Map.of("updated",true);}
    @PostMapping("/api/account/inference/tickets/{id}/execute") public void execute(@AuthenticationPrincipal Identity actor,@PathVariable String id,HttpServletRequest request,HttpServletResponse response)throws IOException{
        byte[] bytes=request.getInputStream().readNBytes(ModelDefaults.MAX_REQUEST_BYTES+1);if(bytes.length>ModelDefaults.MAX_REQUEST_BYTES)throw new ApiException(413,"PAYLOAD_TOO_LARGE","Model request too large");proxy.execute(actor,id,bytes,request,response);
    }
}
