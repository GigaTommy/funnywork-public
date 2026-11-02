package org.dromara.web.directory;

import org.junit.jupiter.api.*;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.controller.system.FunnyWorkerIdentityController;
import org.dromara.system.domain.vo.*;
import org.dromara.system.service.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class MemberReadinessTest {
 @Test void returnsCurrentDepartmentActiveRolesAndEffectivePermissionSnapshot(){
  var users=mock(ISysUserService.class);var permissions=mock(ISysPermissionService.class);var departments=mock(FunnyWorkerUsageDirectory.class);
  var user=new SysUserVo();user.setTenantId("a");user.setUserName("local-member");user.setStatus("0");
  var active=new SysRoleVo();active.setRoleName("查看员");active.setStatus("0");var disabled=new SysRoleVo();disabled.setRoleName("停用角色");disabled.setStatus("1");user.setRoles(List.of(active,disabled));
  when(users.selectUserById(10L)).thenReturn(user);when(permissions.getMenuPermission(10L)).thenReturn(Set.of("funnyworker:desktop:read","funnyworker:tasks:read"));
  when(departments.lookup("a",List.of("10"))).thenReturn(List.of(Map.of("state","MAPPED","departmentName","当前部门")));
  try(var login=mockStatic(LoginHelper.class)){login.when(LoginHelper::getTenantId).thenReturn("a");
   var data=new FunnyWorkerIdentityController(users,permissions,departments).status(10L).getData();
   assertEquals(List.of("查看员"),data.get("roles"));assertEquals(true,data.get("desktopAllowed"));assertEquals("",data.get("source"));assertEquals("当前部门",((Map<?,?>)data.get("department")).get("departmentName"));assertEquals(List.of("funnyworker:desktop:read","funnyworker:tasks:read"),data.get("permissions"));verify(users).checkUserDataScope(10L);
  }
 }
 @Test void foreignTenantDoesNotDisclosePermissionOrDepartment(){
  var users=mock(ISysUserService.class);var permissions=mock(ISysPermissionService.class);var departments=mock(FunnyWorkerUsageDirectory.class);var user=new SysUserVo();user.setTenantId("foreign");when(users.selectUserById(10L)).thenReturn(user);
  try(var login=mockStatic(LoginHelper.class)){login.when(LoginHelper::getTenantId).thenReturn("a");assertEquals(403,new FunnyWorkerIdentityController(users,permissions,departments).status(10L).getCode());verifyNoInteractions(permissions,departments);}
 }
}
