package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity-links")
public class EnterpriseIdentityController {
    private final EnterpriseIdentity identities;
    public EnterpriseIdentityController(EnterpriseIdentity identities){this.identities=identities;}
    @PutMapping public void link(@AuthenticationPrincipal Identity admin,@Valid @RequestBody EnterpriseIdentity.Link body){identities.link(admin,body);}
    @PostMapping("/revoke") public void revoke(@AuthenticationPrincipal Identity admin,@Valid @RequestBody EnterpriseIdentity.Unlink body){identities.unlink(admin,body);}
}
