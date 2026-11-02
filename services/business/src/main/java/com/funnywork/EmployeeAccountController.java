package com.funnywork;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
@RestController
public class EmployeeAccountController {
 private final EmployeeAccounts accounts;private final AuthService auth;private final ManagementDesktopAccounts desktop;private final MobilePushSubscriptions push;private final AccountAvatars avatars;
 public EmployeeAccountController(EmployeeAccounts accounts,AuthService auth,ManagementDesktopAccounts desktop,MobilePushSubscriptions push,AccountAvatars avatars){this.accounts=accounts;this.auth=auth;this.desktop=desktop;this.push=push;this.avatars=avatars;}
 public record Login(@NotBlank @Size(max=80) String username,@NotNull @Size(max=256) String password,@NotBlank @Size(max=120) String device,@Pattern(regexp="ios|android|desktop") String client){}
 public record Password(@NotBlank @Size(max=80) String username,@NotNull @Size(min=12,max=256) String password){}
 @PostMapping("/api/account/login") public Map<String,Object> login(@Valid @RequestBody Login body){var client=AccountClient.from(body.client());if(!desktop.enabled())return accounts.login(body.username(),body.password(),body.device(),client.mobile());
 return desktop.login(body.username(),body.password(),body.device(),client);}
 @GetMapping("/api/account/session") public Map<String,Object> session(@AuthenticationPrincipal Identity actor,@RequestHeader("Authorization") String header){var result=new java.util.LinkedHashMap<String,Object>(header.substring(7).startsWith("ruoyi.")?desktop.session(actor,header.substring(13)):accounts.session(actor,header.substring(7)));result.put("principalId",actor.id());result.put("avatarUrl",avatars.read(actor));return result;}
 public record Avatar(@NotNull @Size(max=350000) String image){}
 @PostMapping("/api/account/avatar") public Map<String,Object> avatar(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Avatar body){avatars.save(actor,body.image());return Map.of("avatarUrl",avatars.read(actor));}
 @PostMapping("/api/account/logout") public Map<String,Object> logout(@AuthenticationPrincipal Identity actor,@RequestHeader("Authorization") String header){push.logout(actor,header.substring(7));if(header.substring(7).startsWith("ruoyi."))desktop.logout(header.substring(13));else auth.logout(actor,header.substring(7));return Map.of("ok",true);}
 @PutMapping("/api/v1/principals/{id}/password") public void password(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody Password body){accounts.password(actor,id,body.username(),body.password());}
}
