package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class EnterpriseAgentPresetsController {
    private final EnterpriseAgentPresets service;
    public EnterpriseAgentPresetsController(EnterpriseAgentPresets service){this.service=service;}
    @GetMapping("/api/v1/model-middleware/agent-presets") public Object list(@AuthenticationPrincipal Identity actor){return service.administration(actor);}
    @PostMapping("/api/v1/model-middleware/agent-presets") public Object create(@AuthenticationPrincipal Identity actor,@Valid @RequestBody EnterpriseAgentPresets.Publish body){return service.publish(actor,null,body);}
    @PutMapping("/api/v1/model-middleware/agent-presets/{id}") public Object publish(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody EnterpriseAgentPresets.Publish body){return service.publish(actor,id,body);}
    @PutMapping("/api/v1/model-middleware/agent-preset-policy") public Object policy(@AuthenticationPrincipal Identity actor,@Valid @RequestBody EnterpriseAgentPresets.Policy body){service.policy(actor,body);return Map.of("updated",true);}
    @GetMapping("/api/account/inference/agent-presets") public Object catalog(@AuthenticationPrincipal Identity actor){return service.catalog(actor);}
}
