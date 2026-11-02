package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1")
public class McpController {
    private final McpBindings bindings;public McpController(McpBindings bindings){this.bindings=bindings;}
    @GetMapping("/mcp-bindings") public Map<String,Object> list(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return bindings.list(actor,zone,after,limit);}
    @GetMapping("/mcp-bindings/candidates") public Map<String,Object> candidates(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return bindings.candidates(actor,zone,after,limit);}
    @PostMapping("/mcp-bindings/{id}/check") public Map<String,Object> check(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam int version){return bindings.check(actor,id,version);}
    @PutMapping("/mcp-bindings") public Map<String,Object> save(@AuthenticationPrincipal Identity actor,@Valid @RequestBody McpBindings.Config body){return bindings.save(actor,body);}
    @GetMapping("/attempts/{attempt}/mcp-tools") public List<String> catalog(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch){return bindings.catalog(node,attempt,epoch);}
    @PostMapping("/attempts/{attempt}/mcp-tools/{tool}/call") public Map<String,Object> call(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@PathVariable String tool,@Valid @RequestBody McpBindings.Call body){return bindings.call(node,attempt,tool,body);}
}
