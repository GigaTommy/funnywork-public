package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1")
public class DocumentController {
    private final DocumentBindings documents;
    public DocumentController(DocumentBindings documents){this.documents=documents;}
    @GetMapping("/tasks/{task}/documents") public Map<String,Object> inspect(@AuthenticationPrincipal Identity actor,@PathVariable String task){return documents.inspect(actor,task);}
    @GetMapping("/tasks/{task}/documents/tool-candidates") public Map<String,Object> tools(@AuthenticationPrincipal Identity actor,@PathVariable String task,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return documents.toolCandidates(actor,task,after,limit);}
    @GetMapping("/tasks/{task}/documents/source-candidates") public Map<String,Object> sources(@AuthenticationPrincipal Identity actor,@PathVariable String task,@RequestParam(defaultValue="") String afterSource,@RequestParam(defaultValue="") String afterHash,@RequestParam(defaultValue="50") int limit){return documents.sourceCandidates(actor,task,afterSource,afterHash,limit);}
    @PutMapping("/tasks/{task}/documents/{tool}") public void bind(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String tool,@Valid @RequestBody DocumentBindings.Binding binding){documents.bind(actor,task,tool,binding);}
    @DeleteMapping("/tasks/{task}/documents/{tool}") public void revoke(@AuthenticationPrincipal Identity actor,@PathVariable String task,@PathVariable String tool){documents.revoke(actor,task,tool);}
    @GetMapping("/attempts/{attempt}/documents") public Map<String,Object> scope(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch){return documents.scope(node,attempt,epoch);}
}
