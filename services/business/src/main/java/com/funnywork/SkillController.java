package com.funnywork;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1")
public class SkillController {
 private final PublishedSkills skills;public SkillController(PublishedSkills skills){this.skills=skills;}
 @GetMapping("/skills") public Map<String,Object> list(@AuthenticationPrincipal Identity actor,@RequestParam String zone,@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="50") int limit){return skills.list(actor,zone,after,limit);}
 @PostMapping("/skills") public Map<String,Object> publish(@AuthenticationPrincipal Identity actor,@Valid @RequestBody PublishedSkills.Publish body){return skills.publish(actor,body);}
 @PostMapping("/skills/{id}/disable") public void disable(@AuthenticationPrincipal Identity actor,@PathVariable String id){skills.disable(actor,id);}
 @GetMapping("/agent-definitions/{agent}/skills") public List<Map<String,Object>> defaults(@AuthenticationPrincipal Identity actor,@PathVariable String agent,@RequestParam String zone){return skills.defaults(actor,agent,zone);}
 @PutMapping("/agent-definitions/{agent}/skills/{skill}") public void bind(@AuthenticationPrincipal Identity actor,@PathVariable String agent,@PathVariable String skill){skills.bind(actor,agent,skill,true);}
 @DeleteMapping("/agent-definitions/{agent}/skills/{skill}") public void unbind(@AuthenticationPrincipal Identity actor,@PathVariable String agent,@PathVariable String skill){skills.bind(actor,agent,skill,false);}
 @GetMapping("/attempts/{attempt}/skills") public List<Map<String,Object>> forAttempt(@AuthenticationPrincipal Identity node,@PathVariable String attempt,@RequestParam long epoch){return skills.forAttempt(node,attempt,epoch);}
}
