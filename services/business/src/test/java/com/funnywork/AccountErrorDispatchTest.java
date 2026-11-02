package com.funnywork;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:account-error-dispatch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class AccountErrorDispatchTest {
 @Autowired AuthService auth;
 @Autowired EmployeeAccounts accounts;
 @LocalServerPort int port;
 private int get(String path,String token) throws Exception {
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
  if(token!=null)request.header("Authorization","Bearer "+token);
  return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();
 }
 @Test void missingOptionalRouteIsNotAnExpiredSession() throws Exception {
  String tenant=AuthService.id(),adminToken=AuthService.id(),principal=AuthService.id(),username="mobile-"+AuthService.id();
  auth.create(AuthService.id(),tenant,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox"),adminToken);
  auth.create(principal,tenant,"USER","Mobile",Set.of("VIEWER"),Set.of("sandbox"),AuthService.id());
  accounts.password(auth.authenticate(adminToken),principal,username,"test-password-1234");
  String token=(String)accounts.login(username,"test-password-1234","iOS test").get("token");
  assertEquals(200,get("/api/account/session",token));
  assertEquals(404,get("/api/account/sync/unsupported-inbox-diagnostic",token));
  assertEquals(200,get("/api/account/session",token));
  assertEquals(401,get("/api/account/sync/unsupported-inbox-diagnostic",null));
  assertEquals(401,get("/api/account/session","invalid"));
  assertEquals(401,get("/error",null));
 }
}
