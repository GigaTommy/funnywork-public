package com.funnywork;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/management-tasks")
public class ManagementTaskController {
    private final ManagementTasks tasks;
    private final ManagementTaskCommands commands;
    private final TaskAttribution attribution;
    private final ManagementTaskContent content;
    private final ManagementTaskBatches batches;
    private final ManagementTaskWorkflow workflow;
    public ManagementTaskController(ManagementTasks tasks,ManagementTaskCommands commands,TaskAttribution attribution,ManagementTaskContent content,ManagementTaskBatches batches,ManagementTaskWorkflow workflow) { this.tasks=tasks;this.commands=commands;this.attribution=attribution;this.content=content;this.batches=batches;this.workflow=workflow; }
    @GetMapping("/members") public Object members(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String query) { return tasks.members(actor,query); }
    @GetMapping public Object list(@AuthenticationPrincipal Identity actor,
        @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String state,
        @RequestParam(defaultValue="") String zone,@RequestParam(defaultValue="") String owner,
        @RequestParam(defaultValue="") String department,@RequestParam(defaultValue="") String project,@RequestParam(defaultValue="") String capability,@RequestParam(defaultValue="0") long from,@RequestParam(defaultValue="0") long until,
        @RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="") String beforeId,@RequestParam(defaultValue="50") int limit) {
        return tasks.list(actor,new ManagementTasks.Filter(query,state,zone,owner,capability,from,until,department,project),before,beforeId,limit);
    }
    @GetMapping("/options") public Object options(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String departmentAfter,@RequestParam(defaultValue="") String projectAfter){return tasks.options(actor,departmentAfter,projectAfter);}
    @GetMapping("/{id}/workflow") public Object workflow(@AuthenticationPrincipal Identity actor,@PathVariable String id){return workflow.inspect(actor,id);}
    @PostMapping("/batch-preview") public Object batchPreview(@AuthenticationPrincipal Identity actor,@RequestBody ManagementTaskBatches.Preview body){return batches.preview(actor,body);}
    @PostMapping("/batches") public Object batch(@AuthenticationPrincipal Identity actor,@RequestHeader("Idempotency-Key") String key,@RequestBody ManagementTaskBatches.Batch body){return batches.submit(actor,key,body);}
    @GetMapping("/batches/{key}") public Object batch(@AuthenticationPrincipal Identity actor,@PathVariable String key){return batches.read(actor,key);}
    @GetMapping("/content-access/{principal}") public Object contentAccess(@AuthenticationPrincipal Identity actor,@PathVariable String principal){return content.access(actor,principal);}
    @PutMapping("/content-access/{principal}") public Object contentAccess(@AuthenticationPrincipal Identity actor,@PathVariable String principal,@RequestBody ManagementTaskContent.Access body){return content.save(actor,principal,body);}
    @GetMapping("/{id}/content-capabilities") public Object contentCapabilities(@AuthenticationPrincipal Identity actor,@PathVariable String id){return content.capabilities(actor,id);}
    @GetMapping("/{id}/units/{unit}/input") public Object input(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String unit){return content.input(actor,id,unit);}
    @GetMapping("/{id}/artifacts") public Object artifacts(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="0") int afterVersion){return content.artifacts(actor,id,after,afterVersion);}
    @GetMapping("/{id}/artifacts/{artifact}/{version}") public Object artifact(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String artifact,@PathVariable int version){return content.artifact(actor,id,artifact,version);}
    @GetMapping("/attribution-members") public Object attributionMembers(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String after){return tasks.attributionMembers(actor,query,after);}
    @GetMapping("/member-contexts/{principal}") public Object memberContext(@AuthenticationPrincipal Identity actor,@PathVariable String principal,@RequestParam String zone){return attribution.assignment(actor,principal,zone);}
    @PutMapping("/member-contexts/{principal}") public Object memberContext(@AuthenticationPrincipal Identity actor,@PathVariable String principal,@RequestBody TaskAttribution.Assignment body){return attribution.assign(actor,principal,body);}
    @GetMapping("/scope-members") public Object scopeMembers(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String after){return tasks.scopeMembers(actor,query,after);}
    @GetMapping("/scope-options") public Object scopeOptions(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String departmentAfter,@RequestParam(defaultValue="") String projectAfter){return tasks.scopeOptions(actor,departmentAfter,projectAfter);}
    @PostMapping("/departments") public Object department(@AuthenticationPrincipal Identity actor,@RequestBody ManagementTasks.Department body){return tasks.department(actor,body);}
    @GetMapping("/scopes/{principal}") public Object limits(@AuthenticationPrincipal Identity actor,@PathVariable String principal){return tasks.limits(actor,principal);}
    @PutMapping("/scopes/{principal}") public Object limits(@AuthenticationPrincipal Identity actor,@PathVariable String principal,@RequestBody ManagementTasks.Limits body){return tasks.saveLimits(actor,principal,body);}
    @GetMapping("/{id}/context-options") public Object contextOptions(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="") String departmentAfter,@RequestParam(defaultValue="") String projectAfter){return tasks.contextOptions(actor,id,departmentAfter,projectAfter);}
    @PutMapping("/{id}/context") public Object context(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody ManagementTasks.Context body){return tasks.assign(actor,id,body);}
    @PostMapping("/{id}/preview") public Object preview(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody ManagementTaskCommands.Preview body){return commands.preview(actor,id,body.action());}
    @PostMapping("/{id}/commands") public Object execute(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody ManagementTaskCommands.Command body){return commands.execute(actor,id,key,body);}
    @GetMapping("/{id}/commands/{key}") public Object receipt(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String key){return commands.receipt(actor,id,key);}
    @GetMapping("/{id}/placements") public Object placements(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="") String beforeId){return tasks.placements(actor,id,before,beforeId);}
    @GetMapping("/{id}") public Object detail(@AuthenticationPrincipal Identity actor,@PathVariable String id) { return tasks.detail(actor,id); }
    @GetMapping("/{id}/events") public Object events(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") long after) { return tasks.events(actor,id,after); }
}
