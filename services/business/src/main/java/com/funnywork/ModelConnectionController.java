package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/model-profiles/{id}")
public class ModelConnectionController {
    private final ModelConnections connections;
    public ModelConnectionController(ModelConnections connections){this.connections=connections;}
    @GetMapping("/connection") public Map<String,Object> read(@AuthenticationPrincipal Identity actor,@PathVariable String id){return connections.read(actor,id);}
    @PutMapping("/connection") public Map<String,Object> save(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody ModelConnections.Config body){return connections.save(actor,id,body);}
    @PostMapping("/probe") public Map<String,Object> probe(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="false") boolean inference){return connections.probe(actor,id,inference);}
}
