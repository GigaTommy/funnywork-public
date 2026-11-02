package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/attempts/{attempt}/computer-actions")
public class ComputerActionController {
    private final ComputerActions actions;
    public ComputerActionController(ComputerActions actions){this.actions=actions;}
    @PostMapping("/lookup") public Map<String,Object> lookup(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody ComputerActions.Action body){return actions.operate(node,attempt,body,"lookup");}
    @PostMapping("/claim") public Map<String,Object> claim(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody ComputerActions.Action body){return actions.operate(node,attempt,body,"claim");}
    @PostMapping("/dispatched") public Map<String,Object> dispatched(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody ComputerActions.Action body){return actions.operate(node,attempt,body,"dispatched");}
}
