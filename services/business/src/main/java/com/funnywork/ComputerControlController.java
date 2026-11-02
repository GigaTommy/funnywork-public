package com.funnywork;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/attempts/{attempt}/computer-control")
public class ComputerControlController {
    public record Ticket(@Min(1) long epoch,@Min(1) long generation) {}
    public record Stopped(@Min(1) long epoch,@Min(1) long generation,@NotNull @AssertTrue Boolean driverStopped) {}
    private final ComputerControl controls;
    public ComputerControlController(ComputerControl controls){this.controls=controls;}
    @PostMapping public Map<String,Object> acquire(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody Requests.Epoch body){return controls.acquire(node,attempt,body.epoch());}
    @GetMapping public Map<String,Object> check(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch,@RequestParam long generation){return controls.check(node,attempt,epoch,generation,false);}
    @PostMapping("/renew") public Map<String,Object> renew(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody Ticket body){return controls.check(node,attempt,body.epoch(),body.generation(),true);}
    @PostMapping("/release") public void release(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody Stopped body){controls.release(node,attempt,body.epoch(),body.generation(),body.driverStopped());}
}
