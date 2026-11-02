package org.dromara.system.controller.system;

import lombok.RequiredArgsConstructor;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.service.FunnyWorkerUsageDirectory;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/funnyworker/usage")
public class FunnyWorkerUsageController {
 private final FunnyWorkerUsageDirectory directory;
 public record Subjects(List<String> userIds){}
 @SaCheckPermission("funnyworker:usage:read")
 @PostMapping("/members")
 public R<Map<String,Object>> members(@RequestBody Subjects body){
  String tenant=LoginHelper.getTenantId();
  return R.ok(Map.of("tenantId",tenant,"observedAt",System.currentTimeMillis(),"basis","CURRENT_DEPARTMENT","items",directory.lookup(tenant,body.userIds())));
 }
}
