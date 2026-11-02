package com.funnywork;

import java.time.Clock;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeAccounts {
 private ModelBudgets budgets;
 @org.springframework.beans.factory.annotation.Autowired void promotionalBudgets(ModelBudgets budgets){this.budgets=budgets;}
 private final JdbcTemplate db; private final AuthService auth; private final Clock clock; private final LocalCredentialPolicy policy; private final Json json; private final TenantLocks locks;
 public EmployeeAccounts(JdbcTemplate db,AuthService auth,Clock clock,LocalCredentialPolicy policy,Json json,TenantLocks locks){this.db=db;this.auth=auth;this.clock=clock;this.policy=policy;this.json=json;this.locks=locks;}
 private static String hash(String password,String salt){
  PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),Base64.getDecoder().decode(salt),210000,256);
  try{return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
  catch(GeneralSecurityException e){throw new IllegalStateException(e);}finally{spec.clearPassword();}
 }
 @Transactional public void password(Identity actor,String principal,String username,String password){
  locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
  var rows=db.queryForList("select * from principals where id=? and tenant_id=? and kind='USER' and enabled=true",principal,actor.tenant());
  if(rows.isEmpty())throw ApiException.missing();
  if(!policy.permits(actor.tenant(),"USER"))throw ApiException.forbidden();
  if(!actor.zones().containsAll(json.strings((String)rows.get(0).get("zones"))))throw ApiException.forbidden();
  if(db.queryForObject("select count(*) from employee_passwords where username=? and principal_id<>?",Integer.class,username,principal)>0)throw ApiException.conflict("USERNAME_EXISTS");
  byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);String salt=Base64.getEncoder().encodeToString(bytes);
  db.update("delete from employee_passwords where principal_id=?",principal);
  db.update("insert into employee_passwords(username,principal_id,salt,password_hash) values(?,?,?,?)",username,principal,salt,hash(password,salt));
  db.update("update api_tokens set revoked=true where principal_id=?",principal);
  auth.audit(actor,"EMPLOYEE_PASSWORD_CHANGED",Map.of("principalId",principal));
 }
 @Transactional(noRollbackFor=ApiException.class) public Map<String,Object> login(String username,String password,String device){return login(username,password,device,false);}
 @Transactional(noRollbackFor=ApiException.class) public Map<String,Object> login(String username,String password,String device,boolean mobile){
  var rows=db.queryForList("select e.*,p.tenant_id,p.enabled from employee_passwords e join principals p on p.id=e.principal_id where username=? for update",username);
  String salt=rows.isEmpty()?"AAAAAAAAAAAAAAAAAAAAAA==":(String)rows.get(0).get("salt");
  if(!rows.isEmpty()&&((Number)rows.get(0).get("retry_after")).longValue()>clock.millis())throw new ApiException(429,"LOGIN_THROTTLED","请稍后重试");
  String computed=hash(password,salt);
  if(rows.isEmpty())throw new ApiException(401,"UNAUTHENTICATED","账号或密码不正确");
  var row=rows.get(0);String principal=(String)row.get("principal_id");
  if(!Boolean.TRUE.equals(row.get("enabled"))||!policy.permits((String)row.get("tenant_id"),"USER")||!MessageDigest.isEqual(computed.getBytes(java.nio.charset.StandardCharsets.UTF_8),((String)row.get("password_hash")).getBytes(java.nio.charset.StandardCharsets.UTF_8))){
   int failures=((Number)row.get("failures")).intValue()+1;
   db.update("update employee_passwords set failures=?,retry_after=? where username=?",failures,clock.millis()+Math.min(300000L,1000L*(1L<<Math.min(failures,8))),username);
   throw new ApiException(401,"UNAUTHENTICATED","账号或密码不正确");
  }
  db.update("update employee_passwords set failures=0,retry_after=0 where username=?",username);
  String token=auth.newToken();long expires=clock.millis()+(mobile?30L*86400000:28800000L);
  db.update("insert into api_tokens values(?,?,?,false)",AuthService.hash(token),principal,expires);
  db.update("insert into employee_sessions values(?,?)",AuthService.hash(token),device);
  auth.audit(auth.authenticate(token),"EMPLOYEE_LOGGED_IN",Map.of("device",device));
  if(budgets!=null)budgets.claim(auth.authenticate(token));
  return Map.of("token",token,"username",username,"device",device,"expires",expires/1000);
 }
 public Map<String,Object> session(Identity actor,String token){
  var rows=db.queryForList("select e.username,s.device,t.expires_at from employee_sessions s join api_tokens t on t.token_hash=s.token_hash join employee_passwords e on e.principal_id=t.principal_id where s.token_hash=? and e.principal_id=?",AuthService.hash(token),actor.id());
  if(rows.isEmpty())throw new ApiException(401,"UNAUTHENTICATED","登录已失效");
  var row=rows.get(0);return Map.of("username",row.get("username"),"device",row.get("device"),"expires",((Number)row.get("expires_at")).longValue()/1000,"tenant",actor.tenant(),"roles",actor.roles());
 }
}
