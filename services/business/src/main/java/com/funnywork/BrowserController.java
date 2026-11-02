package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BrowserController {
    private final BrowserExecution browser;
    public BrowserController(BrowserExecution browser){this.browser=browser;}
    @PostMapping("/attempts/{attempt}/browser-report") public Map<String,Object> report(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody BrowserExecution.Report body){return browser.report(node,attempt,body);}
    @PostMapping("/attempts/{attempt}/browser-actions/lookup") public Map<String,Object> lookup(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody BrowserExecution.Action body){return browser.lookup(node,attempt,body);}
    @PostMapping("/attempts/{attempt}/browser-actions/claim") public Map<String,Object> claim(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody BrowserExecution.Action body){return browser.claim(node,attempt,body);}
    @PostMapping("/attempts/{attempt}/browser-actions/dispatched") public Map<String,Object> dispatched(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@Valid @RequestBody BrowserExecution.Action body){return browser.dispatched(node,attempt,body);}
    @PutMapping("/tasks/{task}/units/{unit}/browser-grant") public void grant(@AuthenticationPrincipal Identity admin,@PathVariable String task,@PathVariable String unit,@Valid @RequestBody BrowserExecution.Grant body){browser.grant(admin,task,unit,body);}
    @DeleteMapping("/tasks/{task}/units/{unit}/browser-grant") public void revoke(@AuthenticationPrincipal Identity admin,@PathVariable String task,@PathVariable String unit){browser.revoke(admin,task,unit);}
    @GetMapping("/attempts/{attempt}/browser-scope") public Map<String,Object> scope(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch){return browser.scope(node,attempt,epoch);}
    @GetMapping("/attempts/{attempt}/browser-attachment") public Map<String,Object> attachment(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch){return browser.attachment(node,attempt,epoch);}
}
