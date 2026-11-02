package com.funnywork;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/management-history")
public class ManagementHistoryController {
    private final ManagementHistory history;
    public ManagementHistoryController(ManagementHistory history){this.history=history;}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal Identity actor,
            @RequestAttribute(value="funnywork.management-validated",required=false) Boolean validated,
            @RequestParam(defaultValue="ALL") String source,@RequestParam(defaultValue="") String owner,
            @RequestParam(defaultValue="") String after){if(!Boolean.TRUE.equals(validated))throw ApiException.forbidden();return history.list(actor,source,owner,after);}
    @GetMapping("/{source}/{id}/content") public Map<String,Object> content(@AuthenticationPrincipal Identity actor,
            @RequestAttribute(value="funnywork.management-validated",required=false) Boolean validated,
            @PathVariable String source,@PathVariable String id,@RequestParam(defaultValue="-1") long after,
            @RequestParam(defaultValue="") String continuation){
        // Legacy business ADMIN tokens must not bypass the identity platform's new permission.
        if(!Boolean.TRUE.equals(validated))throw ApiException.forbidden();
        return history.content(actor,source,id,after,continuation);
    }
}
