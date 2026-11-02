package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/desktop-model-releases")
public class DesktopModelReleaseController {
    private final DesktopModelReleases releases;private final DesktopModelChecks checks;
    public DesktopModelReleaseController(DesktopModelReleases releases,DesktopModelChecks checks){this.releases=releases;this.checks=checks;}
    @PostMapping("/check") public Map<String,Object> check(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopModelReleases.Settings settings,@RequestParam(defaultValue="false") boolean inference){return checks.check(actor,settings,inference);}
    @GetMapping public Map<String,Object> read(@AuthenticationPrincipal Identity actor){return releases.read(actor);}
    @PostMapping public Map<String,Object> publish(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopModelReleases.Settings settings){return releases.publish(actor,settings);}
    @PostMapping("/rollback") public Map<String,Object> rollback(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopModelReleases.Rollback request){return releases.rollback(actor,request);}
}
