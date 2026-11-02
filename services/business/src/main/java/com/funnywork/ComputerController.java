package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks/{task}/units/{unit}/computer-grant")
public class ComputerController {
    private final ComputerGrants grants;
    public ComputerController(ComputerGrants grants){this.grants=grants;}
    @PutMapping public void grant(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String unit,@Valid @RequestBody ComputerGrants.Grant body){grants.grant(actor,task,unit,body);}
    @GetMapping public Map<String,Object> get(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String unit){return grants.get(actor,task,unit);}
    @DeleteMapping public void revoke(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String unit){grants.revoke(actor,task,unit);}
}
