package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
/** Annual-report business API; candidates use the original authenticated desktop exchange. */
@RestController @RequestMapping("/api/v1/annual-reports")
public class AnnualReportsController {
 private final AnnualReports reports;
 public AnnualReportsController(AnnualReports reports){this.reports=reports;}
 @GetMapping("/projects") public Object projects(@AuthenticationPrincipal Identity actor){return reports.projects(actor);}
 @GetMapping("/datasets/{id}/delivery") public Object delivery(@AuthenticationPrincipal Identity actor,@PathVariable String id){return reports.delivery(actor,id);}
 @GetMapping public Object list(@AuthenticationPrincipal Identity actor){return reports.list(actor);}
 @PostMapping("/datasets") public Object create(@AuthenticationPrincipal Identity actor,@Valid @RequestBody AnnualReports.Dataset body){return reports.createDataset(actor,body);}
 @GetMapping("/datasets/{id}") public Object detail(@AuthenticationPrincipal Identity actor,@PathVariable String id){return reports.detail(actor,id);}
 @PutMapping("/datasets/{id}/enabled") public Object enabled(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody ExecutionResources.Switch body){return reports.revoke(actor,id,body);}
 @PostMapping("/compile") public Object compile(@AuthenticationPrincipal Identity actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody AnnualReports.Compile body){return reports.compile(actor,key,body);}
}
