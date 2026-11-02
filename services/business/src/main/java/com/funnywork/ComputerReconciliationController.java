package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/computer-units/{unit}")
public class ComputerReconciliationController {
    private final ComputerReconciliation service;
    public ComputerReconciliationController(ComputerReconciliation service){this.service=service;}
    @PutMapping("/authorization") public void authorize(@AuthenticationPrincipal Identity admin,@PathVariable String unit,@Valid @RequestBody ComputerReconciliation.Authorization body){service.authorize(admin,unit,body);}
    @PutMapping("/verifier") public void bind(@AuthenticationPrincipal Identity admin,@PathVariable String unit,@Valid @RequestBody ComputerReconciliation.Bind body){service.bind(admin,unit,body);}
    @PutMapping("/reviewers/{principal}") public void grant(@AuthenticationPrincipal Identity admin,@PathVariable String unit,@PathVariable String principal){service.grant(admin,unit,principal,true);}
    @DeleteMapping("/reviewers/{principal}") public void revoke(@AuthenticationPrincipal Identity admin,@PathVariable String unit,@PathVariable String principal){service.grant(admin,unit,principal,false);}
    @PostMapping("/reconciliation") public Map<String,Object> reconcile(@AuthenticationPrincipal Identity reviewer,@PathVariable String unit){return service.reconcile(reviewer,unit);}
}
