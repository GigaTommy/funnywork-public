package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ConnectorController {
    private final ConnectorExecution execution;
    public ConnectorController(ConnectorExecution execution) {this.execution=execution;}
    @PostMapping("/tasks/{task}/units/{unit}/operation")
    public Map<String,Object> prepare(@AuthenticationPrincipal Identity owner,@PathVariable String task,@PathVariable String unit,
            @Valid @RequestBody ConnectorExecution.Prepare body) {return execution.prepare(owner,task,unit,body);}
    @PostMapping("/attempts/{attempt}/connector-execute")
    public Map<String,Object> execute(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody Requests.Epoch body) {
        return execution.execute(node,attempt,body.epoch());
    }
}
