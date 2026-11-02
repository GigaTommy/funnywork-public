package com.funnywork;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/recovery/cases")
public class RecoveryController {
    private final RecoveryCases cases;
    public RecoveryController(RecoveryCases cases){this.cases=cases;}
    public record Claim(@NotNull @Min(0) Long expectedVersion){}
    @PostMapping("/discover") public Map<String,Object> discover(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="100") int limit){return cases.discover(actor,zone,limit);}
    @GetMapping public Map<String,Object> page(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String taskId){return cases.page(actor,zone,after,limit,taskId);}
    @PostMapping("/{id}/claim") public Map<String,Object> claim(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody Claim body){return cases.claim(actor,id,body.expectedVersion());}
    @PostMapping("/{id}/evidence") public Map<String,Object> submit(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody RecoveryCases.Submission body){return cases.submitEvidence(actor,id,body);}
    @GetMapping("/{id}/evidence") public Map<String,Object> evidencePage(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){return cases.evidencePage(actor,id,after,limit);}
    @GetMapping("/{id}/evidence/{evidenceId}") public Map<String,Object> evidence(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String evidenceId){return cases.evidence(actor,id,evidenceId);}

    @PostMapping("/{id}/evidence/{evidenceId}/verify") public Map<String,Object> verify(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String evidenceId){return cases.verifyEvidence(actor,id,evidenceId);}

}
