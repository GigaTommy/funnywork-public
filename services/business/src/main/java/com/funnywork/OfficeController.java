package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1")
public class OfficeController {
    private final OfficeTools office;public OfficeController(OfficeTools office){this.office=office;}
    @GetMapping("/attempts/{attempt}/office-tools") public List<String> catalog(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch){return office.catalog(node,attempt,epoch);}
    @PostMapping("/attempts/{attempt}/office-tools/{tool}/call") public Map<String,Object> call(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@PathVariable String tool,@Valid @RequestBody OfficeTools.Call body){return office.call(node,attempt,tool,body);}
    @GetMapping("/artifacts/{id}/office-preview") public Map<String,Object> preview(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam int version,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int textOffset){return office.preview(actor,id,version,offset,limit,textOffset);}
    @GetMapping("/tasks/{task}/office-outputs") public Map<String,Object> outputs(@AuthenticationPrincipal Identity actor,@PathVariable String task,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return office.outputs(actor,task,after,limit);}
}
