package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class DesktopPolicyController {
    private final DesktopPolicies policies;
    public DesktopPolicyController(DesktopPolicies policies){this.policies=policies;}
    @GetMapping("/api/v1/desktop-policy") public Map<String,Object> read(@AuthenticationPrincipal Identity actor){return policies.read(actor);}
    @PostMapping("/api/v1/desktop-policy") public Map<String,Object> publish(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopPolicies.Release request){return policies.publish(actor,request);}
    @PostMapping("/api/v1/desktop-policy/restore") public Map<String,Object> restore(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopPolicies.Restore request){return policies.restore(actor,request);}
    @PostMapping("/api/v1/desktop-policy/preview") public Map<String,Object> preview(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopPolicies.Preview request){return policies.preview(actor,request);}
    @GetMapping("/api/account/policy") public Map<String,Object> issue(@AuthenticationPrincipal Identity actor,@RequestParam String device){return policies.issue(actor,device);}
    @PostMapping("/api/account/policy/receipt") public Map<String,Object> receipt(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopPolicies.Receipt request){return policies.acknowledge(actor,request);}
}
