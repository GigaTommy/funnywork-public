package com.funnywork;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;

@RestController @RequestMapping("/api/account/sync")
public class DesktopInteractionsController {
 @org.springframework.beans.factory.annotation.Autowired private MaintenanceDesktopChannel maintenance;
 @org.springframework.beans.factory.annotation.Autowired private MaintenanceSyncChecks syncChecks;
 @PostMapping("/devices/{id}/maintenance-sync-checks/{target}/renew") public Object renewSyncCheck(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String target,@jakarta.validation.Valid @RequestBody MaintenanceSyncChecks.Renewal body){return syncChecks.renew(actor,id,target,body);}
 @PostMapping("/devices/{id}/maintenance-sync-checks/{target}/stopped") public Object stoppedSyncCheck(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String target,@jakarta.validation.Valid @RequestBody MaintenanceSyncChecks.Stopped body){return syncChecks.stopped(actor,id,target,body);}
 @PostMapping("/devices/{id}/maintenance-sync-checks/{target}/result") public Object syncCheckResult(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String target,@jakarta.validation.Valid @RequestBody MaintenanceSyncChecks.Receipt body){return syncChecks.receipt(actor,id,target,body);}
 private final DesktopInteractions interactions;
 public DesktopInteractionsController(DesktopInteractions interactions){this.interactions=interactions;}
 @GetMapping("/pending") public Map<String,Object> pending(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after){return interactions.pending(actor,after);}
 @GetMapping("/devices") public List<Map<String,Object>> devices(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after){return interactions.devices(actor,after);}
 @GetMapping("/maintenance/features") public Object maintenanceFeatures(@AuthenticationPrincipal Identity actor){return Map.of("clientBuild",true,"executor","funnywork-native-v1","triage",true,"delivery",true,"annualReport","annual-report.v1","syncCheck",Map.of("executor",MaintenanceExecutorProtocol.CODEX,"protocolVersion",1));}
 @PostMapping("/devices/{id}/maintenance/{attempt}/renew") public Object renewMaintenance(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String attempt,@jakarta.validation.Valid @RequestBody MaintenanceDesktopChannel.Renewal body){return maintenance.renew(actor,id,attempt,body);}
 @PostMapping("/devices/{id}/annual-report/{attempt}/failure") public Object annualFailure(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String attempt,@jakarta.validation.Valid @RequestBody AnnualReports.Failure body){return maintenance.annualFailure(actor,id,attempt,body);}
 @PostMapping("/devices/{id}/annual-report/{attempt}/candidate") public Object annualCandidate(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String attempt,@jakarta.validation.Valid @RequestBody AnnualReports.Candidate body){return maintenance.annualCandidate(actor,id,attempt,body);}
 @PostMapping("/devices/{id}/maintenance/{attempt}/result") public Object maintenanceResult(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String attempt,@jakarta.validation.Valid @RequestBody MaintenanceDesktopChannel.Receipt body){return maintenance.report(actor,id,attempt,body);}
 @PostMapping("/devices/{id}/maintenance/{attempt}/upload-authorizations") public Object uploadAuthorization(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String attempt,@jakarta.validation.Valid @RequestBody ExecutionResources.UploadAuthorization body){return maintenance.authorizeUpload(actor,id,attempt,body);}
 @PostMapping("/devices/{id}/maintenance/{attempt}/uploads") public Object upload(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String attempt,@jakarta.validation.Valid @RequestBody ExecutionResources.Upload body){return maintenance.upload(actor,id,attempt,body);}
 @PostMapping("/devices/{id}/commands") public Map<String,Object> create(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopInteractions.Command body){return "workspace".equals(body.kind())?interactions.workspaceCommand(actor,id,body):interactions.createSession(actor,id,body);}
 @GetMapping("/devices/{id}/commands/{commandId}") public Map<String,Object> command(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String commandId){return interactions.deviceCommand(actor,id,commandId);}
 @PostMapping("/devices/{id}/state") public List<Map<String,Object>> publish(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopInteractions.State body){return interactions.exchange(actor,id,body);}
 @PostMapping("/devices/{id}/heartbeat") public Map<String,Object> heartbeat(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopInteractions.State body){interactions.heartbeat(actor,id,body);return Map.of("ok",true);}
 @GetMapping("/sessions/{id}/state") public Map<String,Object> state(@AuthenticationPrincipal Identity actor,@PathVariable String id){return interactions.state(actor,id);}
 @PostMapping("/sessions/{id}/commands") public Map<String,Object> enqueue(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestBody DesktopInteractions.Command body){return interactions.enqueue(actor,id,body);}
 @GetMapping("/sessions/{id}/commands/{commandId}") public Map<String,Object> sessionCommand(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String commandId){return interactions.sessionCommand(actor,id,commandId);}
 @GetMapping("/sessions/{id}/commands") public List<Map<String,Object>> commands(@AuthenticationPrincipal Identity actor,@PathVariable String id){return interactions.commands(actor,id);}
 @PostMapping("/devices/{id}/commands/{commandId}") public Map<String,Object> result(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable String commandId,@RequestBody DesktopInteractions.Result body){interactions.acknowledge(actor,id,commandId,body);return Map.of("ok",true);}
}
