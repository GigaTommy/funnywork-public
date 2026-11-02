package com.funnywork;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/model-middleware/usage")
public class ModelOperationsController {
    private final ModelOperations service;
    public ModelOperationsController(ModelOperations service){this.service=service;}
    @GetMapping("/overview") public Object overview(@AuthenticationPrincipal Identity actor,@ModelAttribute ModelOperations.Filter filter){return service.overview(actor,filter);}
    @GetMapping("/members") public Object members(@AuthenticationPrincipal Identity actor,@ModelAttribute ModelOperations.Filter filter,@RequestParam(defaultValue="") String afterOwner,@RequestParam(defaultValue="50") int limit){return service.members(actor,filter,afterOwner,limit);}
    @GetMapping("/calls") public Object calls(@AuthenticationPrincipal Identity actor,@ModelAttribute ModelOperations.Filter filter,@RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="") String beforeId,@RequestParam(defaultValue="50") int limit){return service.calls(actor,filter,before,beforeId,limit);}
    @GetMapping("/tasks") public Object tasks(@AuthenticationPrincipal Identity actor,@ModelAttribute ModelOperations.Filter filter,@RequestParam(defaultValue="") String afterOwner,@RequestParam(defaultValue="") String afterSession,@RequestParam(defaultValue="") String afterTask,@RequestParam(defaultValue="50") int limit){return service.tasks(actor,filter,afterOwner,afterSession,afterTask,limit);}
}
