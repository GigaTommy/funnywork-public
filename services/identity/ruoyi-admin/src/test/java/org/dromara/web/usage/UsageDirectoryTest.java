package org.dromara.web.usage;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.dromara.system.service.*;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.common.core.exception.ServiceException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class UsageDirectoryTest {
 JdbcTemplate db;ISysUserService users;FunnyWorkerUsageDirectory directory;
 @BeforeEach void setup(){db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));db.execute("create table sys_dept(dept_id bigint,tenant_id varchar(20),dept_name varchar(50),del_flag varchar(1))");users=mock(ISysUserService.class);directory=new FunnyWorkerUsageDirectory(users,db);}
 SysUserVo user(String tenant,Long dept){var user=new SysUserVo();user.setTenantId(tenant);user.setDeptId(dept);return user;}
 @Test void resolvesCurrentDepartmentAndDoesNotGuessMissingMetadata(){
  db.update("insert into sys_dept values(10,'a','Engineering','0')");when(users.selectUserById(1L)).thenReturn(user("a",10L));when(users.selectUserById(2L)).thenReturn(user("a",null));when(users.selectUserById(3L)).thenReturn(user("a",11L));
  var values=directory.lookup("a",List.of("1","2","3"));assertEquals("Engineering",values.get(0).get("departmentName"));assertEquals("UNASSIGNED",values.get(1).get("state"));assertEquals("UNAVAILABLE",values.get(2).get("state"));
  db.update("update sys_dept set dept_name='New name' where dept_id=10");assertEquals("New name",directory.lookup("a",List.of("1")).get(0).get("departmentName"));verify(users,times(2)).checkUserDataScope(1L);
 }
 @Test void deniesForeignAndOutOfScopeSubjectsWithoutDisclosingDepartments(){
  when(users.selectUserById(1L)).thenReturn(user("b",99L));doThrow(new ServiceException("private scope detail")).when(users).checkUserDataScope(2L);
  var values=directory.lookup("a",List.of("1","2","3"));for(var row:values)assertEquals(Set.of("userId","state"),row.keySet());verify(users,never()).selectUserById(2L);
 }
 @Test void validatesEntireBatchBeforeQueryingUsers(){
  for(var ids:List.of(List.<String>of(),List.of("1","1"),List.of("1","0"),List.of("9223372036854775808"),java.util.stream.IntStream.rangeClosed(1,101).mapToObj(String::valueOf).toList()))assertThrows(ServiceException.class,()->directory.lookup("a",ids));
  verifyNoInteractions(users);
 }
}
