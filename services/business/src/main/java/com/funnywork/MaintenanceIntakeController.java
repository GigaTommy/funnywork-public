package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class MaintenanceIntakeController {
    private final MaintenanceIntake intake;private final MaintenanceGithub github;
    public MaintenanceIntakeController(MaintenanceIntake intake,MaintenanceGithub github){this.intake=intake;this.github=github;}
    @GetMapping("/api/account/sync/feedback/repositories") public Object repositories(@AuthenticationPrincipal Identity actor){return intake.feedbackRepositories(actor);}
    @GetMapping("/api/account/sync/feedback") public Object mine(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return intake.page(actor,after,limit,"","",true);}
    @GetMapping("/api/v1/maintenance/intakes/page") public Object page(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="") String state){return intake.page(actor,after,limit,query,state,false);}
    @GetMapping("/api/v1/maintenance/intake-configs/{id}/source-failures") public Object failures(@AuthenticationPrincipal Identity actor,@PathVariable String id){return intake.sourceFailures(actor,id);}
    @PostMapping("/api/account/sync/feedback") public Object feedback(@AuthenticationPrincipal Identity actor,@Valid @RequestBody MaintenanceIntake.Feedback body){return intake.feedback(actor,body);}
    @PostMapping("/api/account/sync/feedback/{id}/supplements") public Object supplement(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceIntake.Supplement body){return intake.supplement(actor,id,body);}
    @GetMapping("/api/account/sync/feedback/{id}") public Object feedback(@AuthenticationPrincipal Identity actor,@PathVariable String id){return intake.detail(actor,id,false);}
    @GetMapping("/api/v1/maintenance/intake-configs") public Object configs(@AuthenticationPrincipal Identity actor){return intake.configurations(actor);}
    @PostMapping("/api/v1/maintenance/intake-configs") public Object config(@AuthenticationPrincipal Identity actor,@Valid @RequestBody MaintenanceIntake.Configuration body){return intake.configure(actor,body);}
    @GetMapping("/api/v1/maintenance/intakes") public Object list(@AuthenticationPrincipal Identity actor){return intake.list(actor);}
    @GetMapping("/api/v1/maintenance/intakes/{id}") public Object detail(@AuthenticationPrincipal Identity actor,@PathVariable String id){return intake.detail(actor,id,true);}
    @PostMapping("/api/v1/maintenance/intakes/{id}/retry") public Object retry(@AuthenticationPrincipal Identity actor,@PathVariable String id){return intake.retry(actor,id);}
    @PostMapping("/api/v1/maintenance/intakes/{id}/recover") public Object recover(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody MaintenanceIntake.Recovery body){return intake.recover(actor,id,body);}
    @PostMapping("/api/v1/maintenance/intake-configs/{id}/github-sync") public Object sync(@AuthenticationPrincipal Identity actor,@PathVariable String id){return github.sync(actor,id);}
    @PostMapping("/api/account/sync/devices/{device}/maintenance-intake/{id}/renew") public Object renew(@AuthenticationPrincipal Identity actor,@PathVariable String device,@PathVariable String id,@Valid @RequestBody MaintenanceIntake.Renewal body){return intake.renew(actor,device,id,body);}
    @PostMapping("/api/account/sync/devices/{device}/maintenance-intake/{id}/result") public Object result(@AuthenticationPrincipal Identity actor,@PathVariable String device,@PathVariable String id,@Valid @RequestBody MaintenanceIntake.Receipt body){return intake.report(actor,device,id,body);}
}
