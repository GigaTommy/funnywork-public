package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.util.*;

/** Legacy receipt compatibility. Shared provider credentials never leave the server. */
@RestController
@RequestMapping("/api/account/model")
public class DesktopModelPublication {
    public record Device(@Pattern(regexp="[a-f0-9]{32}") String node,
                         @Pattern(regexp="[A-Za-z0-9+/]{43}=") String publicKey) {}
    public record Configuration(String tenant, String signingKey, String signingPublicKey,
                                String endpoint, String model, String apiKey, String thinking,
                                int maxTokens, long version) {}
    private final DesktopModelReleases releases;
    private final DesktopModelReceipts receipts;
    public DesktopModelPublication(DesktopModelReleases releases,ObjectMapper mapper,Clock clock,DesktopModelReceipts receipts) {
        this.releases=releases;this.receipts=receipts;
    }
    private Configuration configuration(Identity actor) {return releases.resolve(actor);}
    @GetMapping("/signer")
    public Map<String,String> signer(@AuthenticationPrincipal Identity actor) {
        return Map.of("publicKey",configuration(actor).signingPublicKey());
    }
    @PostMapping("/publication")
    public Map<String,Object> publication(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Device device) {
        configuration(actor); // Revalidate the authenticated tenant before returning the upgrade error.
        throw new ApiException(410,"MODEL_SECRET_PUBLICATION_DISABLED","Shared model keys are no longer delivered to clients; use the inference gateway");
    }
    @PostMapping("/receipt") public Map<String,Object> receipt(@AuthenticationPrincipal Identity actor,@Valid @RequestBody DesktopModelReceipts.Receipt request){return receipts.acknowledge(actor,request);}
}
