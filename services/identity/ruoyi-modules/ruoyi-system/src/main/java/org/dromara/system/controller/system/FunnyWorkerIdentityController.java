package org.dromara.system.controller.system;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.system.service.ISysUserService;
import org.dromara.system.service.ISysPermissionService;
import org.dromara.system.service.FunnyWorkerUsageDirectory;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** Live authority for the Agent service; permissions are queried anew on each request. */
@RestController @RequiredArgsConstructor
@RequestMapping("/funnyworker")
public class FunnyWorkerIdentityController {
 private final ISysUserService users; private final ISysPermissionService permissions; private final FunnyWorkerUsageDirectory departments;
 @cn.dev33.satoken.annotation.SaCheckPermission("system:user:query")
 @GetMapping("/accounts/{id}/status") public R<Map<String,Object>> status(@PathVariable Long id){
  users.checkUserDataScope(id);
  var user=users.selectUserById(id);
  if(user==null || !LoginHelper.getTenantId().equals(user.getTenantId()))return R.fail(403,"无权查看该账号");
  var grants=permissions.getMenuPermission(id);
  return R.ok(Map.of("userId",id.toString(),"username",user.getUserName(),"nickname",java.util.Objects.toString(user.getNickName(),""),
   "enabled","0".equals(user.getStatus()),"source",java.util.Objects.toString(user.getExternalSource(),""),
   "desktopAllowed",grants.contains("*:*:*")||grants.contains("funnyworker:desktop:read"),
   "department",departments.lookup(user.getTenantId(),java.util.List.of(id.toString())).get(0),
   "roles",user.getRoles()==null?java.util.List.of():user.getRoles().stream().filter(role->"0".equals(role.getStatus())).map(role->role.getRoleName()).filter(java.util.Objects::nonNull).toList(),
   "permissions",grants.stream().sorted().toList()));
 }
 @cn.dev33.satoken.annotation.SaCheckPermission("funnyworker:authorization:write")
 @GetMapping("/accounts/{id}") public R<Map<String,Object>> account(@PathVariable Long id){
  var login=LoginHelper.getLoginUser();
  users.checkUserDataScope(id);
  var user=users.selectUserById(id);
  if(user==null || !"0".equals(user.getStatus()) || !login.getTenantId().equals(user.getTenantId()))return R.fail(403,"账号不可授权");
  return R.ok(Map.of("userId",user.getUserId().toString(),"tenantId",user.getTenantId(),"name",user.getUserName()));
 }
 @GetMapping("/identity") public R<Map<String,Object>> identity(){
  var login=LoginHelper.getLoginUser();
  var user=DataPermissionHelper.ignore(()->users.selectUserById(login.getUserId()));
  if(user==null || !"0".equals(user.getStatus()))return R.fail(401,"账号已停用");
  return R.ok(Map.of("userId",user.getUserId().toString(),"tenantId",user.getTenantId(),"username",user.getUserName(),"nickname",java.util.Objects.toString(user.getNickName(),""),"department",java.util.Objects.toString(login.getDeptName(),""),"permissions",permissions.getMenuPermission(user.getUserId())));
 }
}
