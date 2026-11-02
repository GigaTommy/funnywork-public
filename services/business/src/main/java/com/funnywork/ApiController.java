package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final AuthService auth;
    private final ControlService control;
    public ApiController(AuthService auth,ControlService control) {this.auth=auth;this.control=control;}
    @PostMapping("/auth/rotate") public Map<String,Object> rotate(@AuthenticationPrincipal Identity actor,@RequestHeader("Authorization") String header) {return auth.rotate(actor,header.substring(7));}
    @PostMapping("/auth/logout") public ResponseEntity<Void> logout(@AuthenticationPrincipal Identity actor,@RequestHeader("Authorization") String header) {auth.logout(actor,header.substring(7));return ResponseEntity.noContent().build();}
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal Identity actor) { return Map.of("principalId",actor.id(),"tenant",actor.tenant(),"kind",actor.kind(),"roles",actor.roles(),"zones",actor.zones()); }
    @PutMapping("/tenant/admission-policy") public Map<String,Object> admission(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Requests.AdmissionPolicy body) {return control.setAdmissionPolicy(actor,body.maxOutstandingUnits());}
    @GetMapping("/tenant/scheduling-metrics") public Map<String,Object> metrics(@AuthenticationPrincipal Identity actor) {return control.schedulingMetrics(actor);}
    @PutMapping("/tenant/scheduling-policy") public Map<String,Object> policy(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Requests.SchedulingPolicy body) {return control.setSchedulingPolicy(actor,body.maxConcurrency());}
    @PostMapping("/users") public Map<String,Object> user(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Requests.User body) {return auth.addUser(actor,body.name(),body.roles(),body.zones());}
    @PostMapping("/principals/{id}/disable") public ResponseEntity<Void> disable(@AuthenticationPrincipal Identity actor,@PathVariable String id) {auth.disable(actor,id);return ResponseEntity.noContent().build();}
    @GetMapping("/nodes") public Map<String,Object> nodes(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit) {return control.listNodes(actor,after,limit);}
    @PostMapping("/nodes") public Map<String,Object> node(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Requests.Node body) {return control.registerNode(actor,body);}
    @PutMapping("/nodes/{id}/admission") public ResponseEntity<Void> nodeAdmission(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody Requests.NodeAdmission body) {control.nodeAdmission(actor,id,body.paused());return ResponseEntity.noContent().build();}
    @PutMapping("/nodes/source-deployments") public Map<String,Object> sources(@AuthenticationPrincipal Identity node,@Valid @RequestBody Requests.NodeSources body){return control.reportSourceDeployments(node,body);}
    @GetMapping("/nodes/source-deployments") public Map<String,Object> sources(@AuthenticationPrincipal Identity node){return control.sourceDeployments(node);}
    @PostMapping("/nodes/heartbeat") public ResponseEntity<Void> heartbeat(@AuthenticationPrincipal Identity node,@Valid @RequestBody Requests.Heartbeat body) {control.heartbeat(node,body.accepting(),body.reason());return ResponseEntity.noContent().build();}
    @PostMapping("/nodes/poll") public Map<String,Object> poll(@AuthenticationPrincipal Identity node) {return control.poll(node);}
    @PostMapping("/nodes/messages") public List<Map<String,Object>> messages(@AuthenticationPrincipal Identity node,@RequestParam(defaultValue="16") int credit) {return control.messages(node,credit);}
    @PostMapping("/nodes/messages/{id}/ack") public Map<String,Object> acknowledge(@AuthenticationPrincipal Identity node,@PathVariable String id) {return control.acknowledge(node,id);}
    @GetMapping("/agent-definitions/{id}/scheduling-policy") public Map<String,Object> agentPolicy(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam String zone) {return control.agentSchedulingPolicy(actor,id,zone);}
    @PutMapping("/agent-definitions/{id}/scheduling-policy") public ResponseEntity<Void> agentPolicy(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam String zone,@Valid @RequestBody Requests.SchedulingPolicy body) {control.setAgentSchedulingPolicy(actor,id,zone,body.maxConcurrency());return ResponseEntity.noContent().build();}
    @DeleteMapping("/agent-definitions/{id}/scheduling-policy") public ResponseEntity<Void> clearAgentPolicy(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam String zone) {control.setAgentSchedulingPolicy(actor,id,zone,null);return ResponseEntity.noContent().build();}
    @GetMapping("/agent-definitions/page") public Map<String,Object> agentPage(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String capability,@RequestParam(defaultValue="") String query) {return control.agentPage(actor,after,limit,capability,query);}
    @GetMapping("/agent-definitions") public List<Map<String,Object>> agents(@AuthenticationPrincipal Identity actor){return control.availableAgents(actor);}
    @GetMapping("/tasks") public Map<String,Object> tasks(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String zone,@RequestParam(defaultValue="") String query,@RequestParam(defaultValue="0") long from,@RequestParam(defaultValue="0") long until){return control.listTasks(actor,after,limit,state,zone,query,from,until);}
    @PostMapping("/agent-definitions") public Map<String,Object> agent(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Requests.Agent body) {return control.publishAgent(actor,body);}
    @GetMapping("/tasks/summary") public Map<String,Object> taskSummary(@AuthenticationPrincipal Identity actor){return control.taskSummary(actor);}
    @PostMapping("/tasks") public Map<String,Object> create(@AuthenticationPrincipal Identity actor,@RequestHeader(value="Idempotency-Key",required=false) String key,@Valid @RequestBody Requests.Task body) {return control.createTask(actor,key,body);}
    @GetMapping("/tasks/{id}") public Map<String,Object> task(@AuthenticationPrincipal Identity actor,@PathVariable String id) {return control.getTask(actor,id);}
    @PostMapping("/tasks/{id}/plans") public Map<String,Object> appendPlan(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody Requests.PlanAppend body) {return control.appendPlan(actor,id,body);}
    @GetMapping("/tasks/{id}/plans") public List<Map<String,Object>> plans(@AuthenticationPrincipal Identity actor,@PathVariable String id) {return control.plans(actor,id);}
    @GetMapping("/tasks/{id}/plans/{version}") public Map<String,Object> plan(@AuthenticationPrincipal Identity actor,@PathVariable String id,@PathVariable int version,@RequestParam(defaultValue="-1") int after,@RequestParam(defaultValue="5") int limit) {return control.planSnapshot(actor,id,version,after,limit);}
    @GetMapping("/tasks/{id}/events") public List<Map<String,Object>> events(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") long after) {return control.events(actor,id,after);}
    @PostMapping("/tasks/{id}/cancel") public ResponseEntity<Void> cancel(@AuthenticationPrincipal Identity actor,@PathVariable String id) {control.cancel(actor,id);return ResponseEntity.accepted().build();}
    @PutMapping("/tasks/{id}/admission") public Map<String,Object> taskAdmission(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody Requests.TaskAdmission body) {return control.taskAdmission(actor,id,body.paused());}
    @PostMapping("/attempts/{id}/renew") public Map<String,Object> renew(@AuthenticationPrincipal Identity node,@PathVariable String id,@Valid @RequestBody Requests.Epoch body) {return control.renew(node,id,body.epoch());}
    @PostMapping("/attempts/{id}/result") public Map<String,Object> result(@AuthenticationPrincipal Identity node,@PathVariable String id,@Valid @RequestBody Requests.Result body) {return control.result(node,id,body);}
}
