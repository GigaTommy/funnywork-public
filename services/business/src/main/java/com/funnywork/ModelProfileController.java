package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ModelProfileController {
    private final ModelProfiles profiles;
    public ModelProfileController(ModelProfiles profiles){this.profiles=profiles;}
    @GetMapping("/model-profiles") public Map<String,Object> list(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return profiles.list(actor,zone,after,limit);}
    @PostMapping("/model-profiles") public Map<String,Object> create(@AuthenticationPrincipal Identity actor,@Valid @RequestBody ModelProfiles.Profile body){return profiles.save(actor,null,body);}
    @PutMapping("/model-profiles/{id}") public Map<String,Object> update(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody ModelProfiles.Profile body){return profiles.save(actor,id,body);}
    @GetMapping("/model-profiles/{id}/history") public Map<String,Object> history(@AuthenticationPrincipal Identity actor,@PathVariable String id,@RequestParam(defaultValue="0") int before,@RequestParam(defaultValue="50") int limit){return profiles.history(actor,id,before,limit);}
    @PostMapping("/model-profiles/{id}/restore") public Map<String,Object> restore(@AuthenticationPrincipal Identity actor,@PathVariable String id,@Valid @RequestBody ModelProfiles.Restore body){return profiles.restore(actor,id,body);}
    @GetMapping("/nodes/model-profiles/{id}") public Map<String,Object> node(@AuthenticationPrincipal Identity actor,@PathVariable String id){return profiles.forNode(actor,id);}
}
