package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/maintenance/automation")
public class MaintenanceAutomationController {
    private final MaintenanceAutomation service;
    public MaintenanceAutomationController(MaintenanceAutomation service){this.service=service;}
    @GetMapping public Object overview(@AuthenticationPrincipal Identity actor){return service.overview(actor);}
    @GetMapping("/stages/{id}") public Object stage(@AuthenticationPrincipal Identity actor,@PathVariable String id){return service.stageDetail(actor,id);}
    @GetMapping("/problems/{id}/evidence") public Object evidence(@AuthenticationPrincipal Identity actor,@PathVariable String id){return service.evidence(actor,id);}
    @PostMapping("/problems/{id}/runtime-repair") public Object repair(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceAutomation.RuntimeRepair body){return service.registerRuntimeRepair(actor,id,body);}
    @PostMapping("/policies") public Object policy(@AuthenticationPrincipal Identity actor,@Valid @RequestBody MaintenanceAutomation.Policy body){return service.configure(actor,body);}
}
