package org.dromara.web.directory;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Requires audit permission as well as the directory service's organization-wide read authority. */
@RestController
@RequestMapping("/funnyworker/identity-sources/audit")
public class DirectoryAuditController {
    private final DirectoryAudit audit;
    public DirectoryAuditController(DirectoryAudit audit){this.audit=audit;}
    @GetMapping @SaCheckPermission("funnyworker:audit:read")
    public R<Map<String,Object>> page(@RequestParam long from,@RequestParam long until,
        @RequestParam(defaultValue="") String source,@RequestParam(defaultValue="") String reference,
        @RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="50") int limit){
        return R.ok(audit.page(LoginHelper.getTenantId(),LoginHelper.getUserId(),from,until,source,reference,cursor,limit));
    }
}
