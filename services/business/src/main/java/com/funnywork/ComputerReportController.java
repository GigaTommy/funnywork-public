package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/attempts/{attempt}/computer-report")
public class ComputerReportController {
    private final ComputerReports reports;
    public ComputerReportController(ComputerReports reports){this.reports=reports;}
    @PostMapping public Map<String,Object> report(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody ComputerReports.Report body){return reports.report(node,attempt,body);}
}
