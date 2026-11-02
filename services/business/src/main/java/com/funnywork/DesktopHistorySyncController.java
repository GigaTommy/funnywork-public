package com.funnywork;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;

@RestController
@RequestMapping("/api/account/sync")
public class DesktopHistorySyncController {
    private final DesktopHistorySync sync;
    private final DesktopHistorySearch search;
    public DesktopHistorySyncController(DesktopHistorySync sync,DesktopHistorySearch search){this.sync=sync;this.search=search;}
    @GetMapping("/search") public Map<String,Object> search(@AuthenticationPrincipal Identity actor,@RequestParam String query,@RequestParam(defaultValue="") String afterSession,@RequestParam(defaultValue="-1") long afterSeq,@RequestParam(defaultValue="false") boolean archived,@RequestParam(defaultValue="") String deviceID){return search.search(actor,query,afterSession,afterSeq,archived,deviceID);}
    @PostMapping("/devices") public Map<String,Object> device(@AuthenticationPrincipal Identity actor,@RequestBody DesktopHistorySync.Device request){return sync.registerDevice(actor,request);}
    @PostMapping("/devices/{id}/revoke") public Map<String,Object> revoke(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody(required=false) DesktopHistorySync.DeviceAccess request){sync.revokeDevice(actor,id,request);return Map.of("ok",true);}
    @PostMapping("/devices/{id}/restore") public Map<String,Object> restore(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody(required=false) DesktopHistorySync.DeviceAccess request){sync.restoreDevice(actor,id,request);return Map.of("ok",true);}
    @PostMapping("/sessions") public Map<String,Object> register(@AuthenticationPrincipal Identity actor,@RequestBody DesktopHistorySync.Registration request){return sync.registerSession(actor,request);}
    @PostMapping("/sessions/{id}/events") public Map<String,Object> append(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopHistorySync.Batch request){return sync.append(actor,id,request);}
    @GetMapping("/sessions") public List<Map<String,Object>> list(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="100") int limit){return sync.list(actor,after,limit);}
    @GetMapping("/sessions/{id}") public Map<String,Object> conversation(@AuthenticationPrincipal Identity actor,@PathVariable String id){return sync.conversation(actor,id);}
    @PostMapping("/sessions/{id}/title") public Map<String,Object> rename(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopHistorySync.Title request){return sync.rename(actor,id,request);}
    @PostMapping("/sessions/{id}/archive") public Map<String,Object> archive(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopHistorySync.Archive request){return sync.archive(actor,id,request);}
    @GetMapping("/sessions/{id}/events") public Map<String,Object> page(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") long offset,@RequestParam(defaultValue="100") int limit){return sync.page(actor,id,offset,limit);}
}
