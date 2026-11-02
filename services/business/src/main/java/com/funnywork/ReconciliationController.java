package com.funnywork;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/operations")
public class ReconciliationController {
    private final OperationReconciliation reconciliation;
    public ReconciliationController(OperationReconciliation reconciliation){this.reconciliation=reconciliation;}
    @PostMapping("/reconciliation/scan") public Map<String,Object> scan(@AuthenticationPrincipal Identity actor,
        @RequestParam(defaultValue="5") int limit){return reconciliation.scan(actor,limit);}
    @GetMapping("/reconciliation") public Map<String,Object> list(@AuthenticationPrincipal Identity actor,
        @RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit,
        @RequestParam(defaultValue="false") boolean includeResolved){return reconciliation.list(actor,after,limit,includeResolved);}
    @PutMapping("/{id}/reconcilers/{reviewer}") public void grant(@AuthenticationPrincipal Identity admin,@PathVariable String id,@PathVariable String reviewer){reconciliation.grant(admin,id,reviewer,true);}
    @DeleteMapping("/{id}/reconcilers/{reviewer}") public void revoke(@AuthenticationPrincipal Identity admin,@PathVariable String id,@PathVariable String reviewer){reconciliation.grant(admin,id,reviewer,false);}
    @GetMapping("/{id}/reconciliation") public Map<String,Object> read(@AuthenticationPrincipal Identity actor,@PathVariable String id){return reconciliation.read(actor,id);}
    @PostMapping("/{id}/reconciliation") public Map<String,Object> reconcile(@AuthenticationPrincipal Identity actor,@PathVariable String id){return reconciliation.reconcile(actor,id);}
}
