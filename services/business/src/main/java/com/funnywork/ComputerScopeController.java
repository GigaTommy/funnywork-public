package com.funnywork;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/attempts/{attempt}/computer-scope")
public class ComputerScopeController {
    private final ComputerGrants grants;
    public ComputerScopeController(ComputerGrants grants){this.grants=grants;}
    @GetMapping public Map<String,Object> scope(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch){return grants.scope(node,attempt,epoch);}
}
