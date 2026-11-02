package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ArtifactController {
    private final ArtifactService artifacts;
    public ArtifactController(ArtifactService artifacts) {this.artifacts=artifacts;}
    @PostMapping("/tasks/{task}/artifacts") public ResponseEntity<Void> attach(@AuthenticationPrincipal Identity actor,@PathVariable String task,@Valid @RequestBody ArtifactService.Attachment body) {artifacts.attach(actor,task,body);return ResponseEntity.noContent().build();}
    @GetMapping("/tasks/{task}/artifacts") public java.util.List<Map<String,Object>> taskArtifacts(@AuthenticationPrincipal Identity actor,@PathVariable String task) {return artifacts.taskArtifacts(actor,task);}
    @GetMapping("/tasks/{task}/units/{unit}/result-content") public Map<String,Object> taskResult(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String unit) {return artifacts.taskResult(actor,task,unit);}
    @PostMapping("/artifacts") public Map<String,Object> create(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ArtifactService.Create body,@RequestHeader(value="Idempotency-Key",required=false) String key) {return artifacts.create(actor,body,key);}
    @PostMapping("/artifacts/{id}/versions") public Map<String,Object> update(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody ArtifactService.Revision body) {return artifacts.update(actor,id,body);}
    @GetMapping("/artifacts/{id}") public Map<String,Object> read(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") int version) {return artifacts.read(actor,id,version);}
    @PutMapping("/artifacts/{id}/readers/{principal}") public ResponseEntity<Void> grant(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String principal) {artifacts.grant(actor,id,principal,true);return ResponseEntity.noContent().build();}
    @DeleteMapping("/artifacts/{id}/readers/{principal}") public ResponseEntity<Void> revoke(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String principal) {artifacts.grant(actor,id,principal,false);return ResponseEntity.noContent().build();}
    @DeleteMapping("/artifacts/{id}") public ResponseEntity<Void> delete(@AuthenticationPrincipal Identity actor,@PathVariable String id) {artifacts.delete(actor,id);return ResponseEntity.noContent().build();}
}
