package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public class ExecutionResourcesController {
 private final ExecutionResources resources;
 private final ExecutionPreview preview;
 public ExecutionResourcesController(ExecutionResources resources,ExecutionPreview preview){this.resources=resources;this.preview=preview;}
 @GetMapping("/api/v1/execution-resources/devices") public Object devices(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="") String query){return preview.devices(actor,after,query);}
 @PostMapping("/api/v1/execution-placement/preview") public Object preview(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ExecutionPreview.Request body){return preview.preview(actor,body);}
 @GetMapping("/api/v1/execution-resources") public Object list(@AuthenticationPrincipal Identity actor){return resources.overview(actor);}
 @PostMapping("/api/v1/execution-projects") public Object project(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ExecutionResources.Named body){return resources.create(actor,"projects",body);}
 @PostMapping("/api/v1/execution-pools") public Object pool(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ExecutionResources.Named body){return resources.create(actor,"pools",body);}
 @PostMapping("/api/v1/execution-profiles") public Object profile(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ExecutionResources.Profile body){return resources.profile(actor,body);}
 @PutMapping("/api/v1/execution-{kind:projects|pools|profiles}/{id}/enabled") public Object enable(@AuthenticationPrincipal Identity actor,@PathVariable String kind,@PathVariable String id,@Valid @RequestBody ExecutionResources.Switch body){return resources.enable(actor,kind,id,body);}
 @PutMapping("/api/v1/execution-pools/{id}/members") public Object member(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody ExecutionResources.Member body){return resources.member(actor,id,body);}
 @PostMapping("/api/v1/execution-grants") public Object grant(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ExecutionResources.Grant body){return resources.grant(actor,body);}
 @PostMapping("/api/v1/execution-routes") public Object route(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ExecutionResources.Route body){return resources.route(actor,body);}
 @GetMapping("/api/v1/tasks/{id}/placement") public Object placement(@AuthenticationPrincipal Identity actor,@PathVariable String id){return resources.placement(actor,id);}
}
