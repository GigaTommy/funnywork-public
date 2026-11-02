package com.funnywork;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/management-usage")
public class ManagementUsageController {
 private final ManagementUsage usage;private final ManagementUsageDepartments departments;
 public ManagementUsageController(ManagementUsage usage,ManagementUsageDepartments departments){this.usage=usage;this.departments=departments;}
 @GetMapping public Map<String,Object> read(@AuthenticationPrincipal Identity actor,@RequestParam long from,@RequestParam long until,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit,@RequestHeader(value="Authorization",required=false) String authorization,@RequestHeader(value="X-FunnyWorker-Management",required=false) String management){var page=usage.read(actor,from,until,after,limit);String token="1".equals(management)&&authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):null;return departments.enrich(actor,token,page);}
}
