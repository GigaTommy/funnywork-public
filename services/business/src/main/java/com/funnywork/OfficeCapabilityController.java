package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/office-capabilities")
public class OfficeCapabilityController {
    private final OfficeCapabilities office;public OfficeCapabilityController(OfficeCapabilities office){this.office=office;}
    @GetMapping public Map<String,Object> status(@AuthenticationPrincipal Identity actor,@RequestParam String agentId,@RequestParam String zone){return office.status(actor,agentId,zone);}
    @PostMapping public Map<String,Object> configure(@AuthenticationPrincipal Identity actor,@Valid @RequestBody OfficeCapabilities.Configure body){return office.configure(actor,body);}
}
