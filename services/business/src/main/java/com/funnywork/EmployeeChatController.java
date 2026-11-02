package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/chats")
public class EmployeeChatController {
    private final EmployeeChats chats;private final WorkContexts contexts;
    public EmployeeChatController(EmployeeChats chats,WorkContexts contexts){this.chats=chats;this.contexts=contexts;}
    @GetMapping public Map<String,Object> list(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="") String after){return chats.list(actor,after);}
    @GetMapping("/{id}") public Map<String,Object> get(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="50") int before){return chats.inspect(actor,id,before);}
    @GetMapping("/{id}/events") public java.util.List<Map<String,Object>> events(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") long after){return chats.events(actor,id,after);}
    @PostMapping("/{id}/continue") public Map<String,Object> continueChat(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody EmployeeChats.Continuation body){return chats.continueChat(actor,id,key,body);}
    @GetMapping("/{id}/context") public Map<String,Object> context(@AuthenticationPrincipal Identity actor,@PathVariable String id){return contexts.get(actor,id);}
    @GetMapping("/{id}/context/materials") public Map<String,Object> materials(@AuthenticationPrincipal Identity actor,@PathVariable String id){return contexts.materials(actor,id);}
    @PutMapping("/{id}/context") public Map<String,Object> saveContext(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody WorkContexts.Body body){return contexts.save(actor,id,key,body);}
    @DeleteMapping("/{id}/context") public Map<String,Object> clearContext(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestParam int expectedVersion){return contexts.clear(actor,id,key,expectedVersion);}
    @PostMapping("/{id}/instructions") public Map<String,Object> supplement(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@RequestBody EmployeeChats.Instruction body){return chats.supplement(actor,id,key,body);}
    @PostMapping public Map<String,Object> create(@AuthenticationPrincipal Identity actor,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Requests.Task body){return chats.send(actor,null,key,body);}
    @PostMapping("/{id}/turns") public Map<String,Object> send(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Requests.Task body){return chats.send(actor,id,key,body);}
}
