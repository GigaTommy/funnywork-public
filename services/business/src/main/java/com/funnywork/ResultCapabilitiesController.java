package com.funnywork;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;
@RestController
public class ResultCapabilitiesController {
    private final DesktopAttachments attachments;
    public ResultCapabilitiesController(DesktopAttachments attachments){this.attachments=attachments;}
    @GetMapping("/api/account/sync/result-capabilities") public Map<String,Object> read(@AuthenticationPrincipal Identity actor){return attachments.capabilities(actor);}
}
