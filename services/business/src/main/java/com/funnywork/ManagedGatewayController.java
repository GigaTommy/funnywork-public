package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/managed-gateways")
public class ManagedGatewayController {
    private final ManagedGateways gateways;
    public ManagedGatewayController(ManagedGateways gateways){this.gateways=gateways;}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return gateways.list(actor,zone,after,limit);}
    @GetMapping("/contracts") public Map<String,Object> contracts(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return gateways.contracts(actor,zone,after,limit);}
    @PostMapping("/{id}/check") public Map<String,Object> check(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam int version){return gateways.check(actor,id,version);}
    @PutMapping public Map<String,Object> save(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ManagedGateways.Config body){return gateways.save(actor,body);}
}
