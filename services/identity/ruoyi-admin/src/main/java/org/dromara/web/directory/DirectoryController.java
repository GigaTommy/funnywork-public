package org.dromara.web.directory;

import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Directory grants require organization-wide scope; ordinary departmental roles cannot synchronize users. */
@RestController
@RequestMapping("/funnyworker/identity-sources")
public class DirectoryController {
    private final DirectoryRegistry registry;
    private final DirectorySync sync;
    private final DirectoryJobs jobs;
    private final DirectoryAccess access;
    private final DirectoryPreviews previews;
    public DirectoryController(DirectoryRegistry registry,DirectorySync sync,DirectoryJobs jobs,DirectoryAccess access,DirectoryPreviews previews){this.registry=registry;this.sync=sync;this.jobs=jobs;this.access=access;this.previews=previews;}
    private String tenant(boolean write){String tenant=LoginHelper.getTenantId();access.require(tenant,LoginHelper.getUserId(),write);return tenant;}
    @GetMapping("/access") public R<Map<String,Boolean>> capabilities(){
        String tenant=LoginHelper.getTenantId();long actor=LoginHelper.getUserId();
        return R.ok(Map.of("canRead",access.allowed(tenant,actor,false),"canWrite",access.allowed(tenant,actor,true)));
    }
    @GetMapping public R<List<Map<String,Object>>> sources(){String tenant=tenant(false);return R.ok(registry.sources(tenant).stream().map(s->Map.<String,Object>of("id",s.id(),"departmentMappings",s.departments().size())).toList());}
    @PostMapping("/{id}/sync") @Log(title="统一身份用户同步",businessType=BusinessType.UPDATE)
    public R<Map<String,Object>> synchronize(@PathVariable String id,@RequestParam(defaultValue="false") boolean full){String tenant=tenant(true);return R.ok(sync.sync(tenant,id,LoginHelper.getUserId(),full));}
    @GetMapping("/{id}/runs") public R<List<Map<String,Object>>> runs(@PathVariable String id){return R.ok(sync.runs(tenant(false),id));}
    @PostMapping("/{id}/jobs") @Log(title="提交身份同步",businessType=BusinessType.UPDATE)
    public R<Map<String,Object>> submit(@PathVariable String id,@RequestParam(defaultValue="false") boolean full){return R.ok(jobs.submit(tenant(true),id,LoginHelper.getUserId(),full));}
    @GetMapping("/{id}/jobs") public R<List<Map<String,Object>>> jobs(@PathVariable String id){return R.ok(jobs.list(tenant(false),id));}
    @GetMapping("/{id}/schedule") public R<Map<String,Object>> schedule(@PathVariable String id){return R.ok(jobs.schedule(tenant(false),id));}
    public record Schedule(int intervalSeconds,boolean enabled){}
    @PutMapping("/{id}/schedule") @Log(title="身份同步计划",businessType=BusinessType.UPDATE)
    public R<Void> schedule(@PathVariable String id,@RequestBody Schedule body){jobs.schedule(tenant(true),id,LoginHelper.getUserId(),body.intervalSeconds(),body.enabled());return R.ok();}
    @PostMapping("/{id}/previews") @Log(title="预览身份同步",businessType=BusinessType.UPDATE)
    public R<Map<String,Object>> preview(@PathVariable String id,@RequestParam(defaultValue="false") boolean full){return R.ok(jobs.submitPreview(tenant(true),id,LoginHelper.getUserId(),full));}
    @GetMapping("/{id}/previews/{preview}") public R<Map<String,Object>> preview(@PathVariable String id,@PathVariable String preview,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){return R.ok(previews.get(tenant(false),id,LoginHelper.getUserId(),preview,offset,limit));}
    @PostMapping("/{id}/previews/{preview}/confirm") @Log(title="确认身份同步",businessType=BusinessType.UPDATE)
    public R<Map<String,Object>> approve(@PathVariable String id,@PathVariable String preview){return R.ok(previews.approve(tenant(true),id,LoginHelper.getUserId(),preview));}
}
