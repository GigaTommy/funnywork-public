package com.funnywork;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Authenticated startup configuration using the same policy and model admission services as individual APIs. */
@RestController
public class DesktopStartupController {
    private final ModelMiddleware models;
    private final DesktopPolicies policies;
    public DesktopStartupController(ModelMiddleware models, DesktopPolicies policies) {
        this.models = models;
        this.policies = policies;
    }
    public record Request(
        @NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String modelDevice,
        @NotNull @Pattern(regexp="[a-f0-9]{32}") String policyDevice) {}

    @PostMapping("/api/account/startup")
    public Map<String,Object> startup(@AuthenticationPrincipal Identity actor, @Valid @RequestBody Request request) {
        var bootstrap = models.bootstrap(actor);
        if (!Boolean.TRUE.equals(bootstrap.get("enabled"))) throw ApiException.conflict("MODEL_ENTRY_DISABLED");
        // Issue no credential when policy or catalog validation fails.
        var policy = policies.issue(actor, request.policyDevice());
        var catalog = Map.of("items", models.catalog(actor));
        var credential = models.credential(actor, request.modelDevice());
        return Map.of("schema", 1, "inference", Map.of("bootstrap", bootstrap, "catalog", catalog, "credential", credential), "policy", policy);
    }
}
