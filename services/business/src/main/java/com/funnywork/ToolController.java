package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ToolController {
    private final ToolRegistry registry;
    public ToolController(ToolRegistry registry) {this.registry=registry;}
    @GetMapping("/tool-templates") public java.util.List<Map<String,Object>> templates(@AuthenticationPrincipal Identity actor) {return registry.templates(actor);}
    @PostMapping("/tool-templates/{template}/publish") public Map<String,Object> publishTemplate(@AuthenticationPrincipal Identity actor,@PathVariable String template,@Valid @RequestBody ToolRegistry.TemplatePublish body) {return registry.publishTemplate(actor,template,body);}
    @GetMapping("/agent-definitions/{agent}/tools") public Map<String,Object> agentTools(@AuthenticationPrincipal Identity actor,@PathVariable String agent,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit) {return registry.agentTools(actor,agent,zone,after,limit);}
    @PutMapping("/agent-definitions/{agent}/tools/{tool}") public ResponseEntity<Void> agentGrant(@AuthenticationPrincipal Identity actor,@PathVariable String agent,@PathVariable String tool) {registry.agentDefault(actor,agent,tool,true);return ResponseEntity.noContent().build();}
    @DeleteMapping("/agent-definitions/{agent}/tools/{tool}") public ResponseEntity<Void> agentRevoke(@AuthenticationPrincipal Identity actor,@PathVariable String agent,@PathVariable String tool) {registry.agentDefault(actor,agent,tool,false);return ResponseEntity.noContent().build();}
    @GetMapping("/tasks/{task}/tools") public java.util.List<Map<String,Object>> catalog(@AuthenticationPrincipal Identity actor,@PathVariable String task) {return registry.catalog(actor,task);}
    @PostMapping("/tool-versions") public Map<String,Object> publish(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ToolRegistry.Publish body) {return registry.publish(actor,body);}
    @PostMapping("/tool-versions/{id}/disable") public ResponseEntity<Void> disable(@AuthenticationPrincipal Identity actor,@PathVariable String id) {registry.disable(actor,id);return ResponseEntity.noContent().build();}
    @PutMapping("/tasks/{task}/tools/{tool}") public ResponseEntity<Void> grant(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String tool) {registry.grant(actor,task,tool,true);return ResponseEntity.noContent().build();}
    @DeleteMapping("/tasks/{task}/tools/{tool}") public ResponseEntity<Void> revoke(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String tool) {registry.grant(actor,task,tool,false);return ResponseEntity.noContent().build();}
}
