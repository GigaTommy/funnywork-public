package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceRequirementsController {
    private final MaintenanceRequirements requirements;
    public MaintenanceRequirementsController(MaintenanceRequirements requirements){this.requirements=requirements;}
    @PostMapping("/requirements/import") public Object importExisting(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after){return requirements.importExisting(actor,after);}
    @GetMapping("/requirements/catalog") public Object catalog(@AuthenticationPrincipal Identity actor){return requirements.catalog(actor);}
    @GetMapping("/requirements") public Object page(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String repositoryId,@RequestParam(defaultValue="") String state){return requirements.page(actor,after,limit,query,repositoryId,state);}
    @PostMapping("/requirements") public Object create(@AuthenticationPrincipal Identity actor,@RequestHeader(value="Idempotency-Key",required=false) String requestKey,@Valid @RequestBody MaintenanceRequirements.Draft body){return requirements.create(actor,requestKey,body);}
    @GetMapping("/requirements/{id}") public Object detail(@AuthenticationPrincipal Identity actor,@PathVariable String id){return requirements.detail(actor,id);}
    @PostMapping("/requirements/{id}") public Object save(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceRequirements.Draft body){return requirements.save(actor,id,body);}
    @PostMapping("/requirements/{id}/queue") public Object queue(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceRequirements.Version body){return requirements.queue(actor,id,body);}
    @PostMapping("/requirements/{id}/impact") public Object impact(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceRequirements.Version body)throws Exception{return requirements.analyze(actor,id,body);}
    @GetMapping("/requirement-dependencies/{id}") public Object dependencies(@AuthenticationPrincipal Identity actor,@PathVariable String id){return requirements.dependencies(actor,id);}
    @PostMapping("/requirement-dependencies/{id}") public Object dependencies(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceRequirements.DependencyMap body){return requirements.dependencies(actor,id,body);}
}
