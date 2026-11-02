package org.dromara.system.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Current department metadata for explicitly scoped activity-report subjects. */
@Service
@RequiredArgsConstructor
public class FunnyWorkerUsageDirectory {
 private final ISysUserService users;
 private final JdbcTemplate db;
 public List<Map<String,Object>> lookup(String tenant,List<String> ids){
  if(tenant==null||tenant.isBlank()||ids==null||ids.isEmpty()||ids.size()>100||new HashSet<>(ids).size()!=ids.size())throw new ServiceException("每批需提供 1 至 100 个不同账号");
  var parsed=new ArrayList<Long>();
  for(String id:ids){try{if(id==null||!id.matches("[1-9][0-9]{0,18}"))throw new NumberFormatException();parsed.add(Long.parseLong(id));}catch(NumberFormatException e){throw new ServiceException("账号编号无效");}}
  var result=new ArrayList<Map<String,Object>>();
  for(int i=0;i<ids.size();i++){
   String id=ids.get(i);Long numeric=parsed.get(i);
   try{users.checkUserDataScope(numeric);}catch(ServiceException denied){result.add(Map.of("userId",id,"state","UNAVAILABLE"));continue;}
   var user=users.selectUserById(numeric);
   if(user==null||!tenant.equals(user.getTenantId())){result.add(Map.of("userId",id,"state","UNAVAILABLE"));continue;}
   if(user.getDeptId()==null){result.add(Map.of("userId",id,"state","UNASSIGNED"));continue;}
   var departments=db.queryForList("select dept_name from sys_dept where tenant_id=? and dept_id=? and del_flag='0'",tenant,user.getDeptId());
   if(departments.isEmpty()){result.add(Map.of("userId",id,"state","UNAVAILABLE"));continue;}
   result.add(Map.of("userId",id,"state","MAPPED","departmentId",user.getDeptId().toString(),"departmentName",Objects.toString(departments.get(0).get("dept_name"),"")));
  }
  return result;
 }
}
