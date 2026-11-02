package com.funnywork;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;

@RestController
@RequestMapping("/api/account/sync")
public class DesktopDeviceLinksController {
 private final DesktopDeviceLinks links;
 public DesktopDeviceLinksController(DesktopDeviceLinks links){this.links=links;}
 @PostMapping("/devices/{id}/link") public Map<String,Object> create(@AuthenticationPrincipal Identity actor,@PathVariable String id){return links.create(actor,id);}
 @PostMapping("/device-links/{id}/inspect") public Map<String,Object> inspect(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopDeviceLinks.Secret secret){return links.inspect(actor,id,secret);}
 @PostMapping("/device-links/{id}/confirm") public Map<String,Object> confirm(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopDeviceLinks.Secret secret){return links.confirm(actor,id,secret);}
 @PostMapping("/device-links/{id}/cancel") public Map<String,Object> cancel(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopDeviceLinks.Secret secret){links.cancel(actor,id,secret);return Map.of("ok",true);}
}
