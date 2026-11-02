package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/nodes/{node}/certificate")
public class NodeCertificateController {
    private final NodeCertificates certificates;
    public NodeCertificateController(NodeCertificates certificates){this.certificates=certificates;}
    @PutMapping public Map<String,Object> bind(@AuthenticationPrincipal Identity admin,@PathVariable String node,@Valid @RequestBody NodeCertificates.Binding body){return certificates.bind(admin,node,body);}
    @DeleteMapping public void revoke(@AuthenticationPrincipal Identity admin,@PathVariable String node){certificates.revoke(admin,node);}
}
