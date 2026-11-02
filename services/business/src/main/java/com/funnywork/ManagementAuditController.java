package com.funnywork;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/management-audit")
public class ManagementAuditController {
 private final ManagementAudit audit;
 public ManagementAuditController(ManagementAudit audit){this.audit=audit;}
 @GetMapping public Map<String,Object> page(@AuthenticationPrincipal Identity actor,@RequestParam long from,@RequestParam long until,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String reference){return audit.page(actor,from,until,after,limit,type,reference);}
}
