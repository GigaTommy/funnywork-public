package com.funnywork;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/desktop-devices")
public class ManagedDesktopDevicesController {
    private final ManagedDesktopDevices devices;
    public ManagedDesktopDevicesController(ManagedDesktopDevices devices){this.devices=devices;}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="ALL") String state,@RequestParam(defaultValue="") String query){return devices.list(actor,after,state,query);}
    @PostMapping("/{id}/revoke") public Map<String,Object> revoke(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopHistorySync.DeviceAccess request){return devices.access(actor,id,request,true);}
    @PostMapping("/{id}/restore") public Map<String,Object> restore(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopHistorySync.DeviceAccess request){return devices.access(actor,id,request,false);}
}
