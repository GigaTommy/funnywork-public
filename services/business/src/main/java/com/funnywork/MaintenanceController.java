package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class MaintenanceController {
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceDesktopChannel desktops;
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceDelivery delivery;
    @GetMapping("/maintenance/delivery-policies") public Object policies(@AuthenticationPrincipal Identity actor){return delivery.policies(actor);}
    @PostMapping("/maintenance/delivery-policies") public Object policy(@AuthenticationPrincipal Identity actor,@Valid @RequestBody MaintenanceDelivery.Policy body){return delivery.configure(actor,body);}
    @PostMapping("/maintenance/problems/{id}/verify") public Object verify(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceDelivery.Action body){return delivery.verify(actor,id,body);}
    @PostMapping("/maintenance/problems/{id}/deliver") public Object deliver(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceDelivery.Action body){return delivery.deliver(actor,id,body);}
    @org.springframework.beans.factory.annotation.Autowired private MaintenanceSyncChecks syncChecks;
    @GetMapping("/maintenance/sync-checks/devices/{id}/capabilities") public Object syncCapabilities(@AuthenticationPrincipal Identity actor,@PathVariable String id){return syncChecks.capabilities(actor,id);}
    @PostMapping("/maintenance/sync-checks") public Object createSyncCheck(@AuthenticationPrincipal Identity actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MaintenanceSyncChecks.Create body){return syncChecks.create(actor,key,body);}
    @GetMapping("/maintenance/sync-checks") public Object syncChecks(@AuthenticationPrincipal Identity actor){return syncChecks.list(actor);}
    @GetMapping("/maintenance/sync-checks/{id}") public Object syncCheck(@AuthenticationPrincipal Identity actor,@PathVariable String id){return syncChecks.detail(actor,id);}
    private final MaintenanceService service;
    private final NodeEnrollment enrollment;
    public MaintenanceController(MaintenanceService service,NodeEnrollment enrollment){this.service=service;this.enrollment=enrollment;}
    @PostMapping("/maintenance/device-bindings") public Object bind(@AuthenticationPrincipal Identity actor,@Valid @RequestBody MaintenanceDesktopChannel.Binding body){return desktops.bind(actor,body);}
    @PostMapping("/maintenance/node-enrollments") public Object enroll(@AuthenticationPrincipal Identity actor,@Valid @RequestBody NodeEnrollment.Issue body){
        if(!Capabilities.REPOSITORY.equals(body.node().capability()) || body.node().capacity()!=1)throw ApiException.conflict("CAPABILITY_MISMATCH");
        return enrollment.issue(actor,body);
    }
    @GetMapping("/maintenance/repositories") public Object repositories(@AuthenticationPrincipal Identity actor){return service.repositories(actor);}
    @PostMapping("/maintenance/repositories") public Object repository(@AuthenticationPrincipal Identity actor,@Valid @RequestBody MaintenanceService.Repository body){return service.repository(actor,body);}
    @PutMapping("/maintenance/repositories/{id}/checks") public Object checks(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceService.RepositoryChecks body){return service.checks(actor,id,body);}
    @GetMapping("/maintenance/problems") public Object problems(@AuthenticationPrincipal Identity actor){return service.list(actor);}
    @PostMapping("/maintenance/problems") public Object create(@AuthenticationPrincipal Identity actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody MaintenanceService.Problem body){return service.create(actor,key,body);}
    @GetMapping("/maintenance/problems/{id}") public Object detail(@AuthenticationPrincipal Identity actor,@PathVariable String id){return service.detail(actor,id);}
    @PostMapping("/maintenance/problems/{id}/decision") public Object decide(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceService.Decision body){return service.decide(actor,id,body);}
    @PostMapping("/maintenance/nodes/profile") public ResponseEntity<Void> profile(@AuthenticationPrincipal Identity node,@Valid @RequestBody MaintenanceService.Profile body){service.profile(node,body);return ResponseEntity.noContent().build();}
    @GetMapping("/attempts/{id}/maintenance/spec") public Object spec(@AuthenticationPrincipal Identity node,@PathVariable String id,@RequestParam long epoch){return service.spec(node,id,epoch);}
    @PostMapping("/attempts/{id}/maintenance/result") public Object result(@AuthenticationPrincipal Identity node,@PathVariable String id,@Valid @RequestBody MaintenanceService.Report body){return service.report(node,id,body);}
}
