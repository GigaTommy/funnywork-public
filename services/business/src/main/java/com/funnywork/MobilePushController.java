package com.funnywork;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;

@RestController @RequestMapping("/api/account/push")
public class MobilePushController {
    private final MobilePushSubscriptions subscriptions;
    public MobilePushController(MobilePushSubscriptions subscriptions){this.subscriptions=subscriptions;}
    @GetMapping public Map<String,Object> capabilities(@AuthenticationPrincipal Identity actor){return subscriptions.capabilities(actor);}
    @PutMapping("/installations/{id}") public Map<String,Object> register(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("Authorization") String authorization,@RequestBody MobilePushSubscriptions.Registration body){return subscriptions.register(actor,id,authorization.substring(7),body);}
    @DeleteMapping("/installations/{id}") public Map<String,Object> remove(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("X-Installation-Secret") String secret,@RequestHeader(value="X-Installation-Revision",required=false) Long revision){subscriptions.remove(actor,id,secret,revision);return Map.of("ok",true);}
}
