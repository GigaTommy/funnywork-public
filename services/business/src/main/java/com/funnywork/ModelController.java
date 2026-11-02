package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/attempts")
public class ModelController {
    private final ModelExecution model;
    private final ControlService control;
    public ModelController(ModelExecution model,ControlService control) {this.model=model;this.control=control;}
    @GetMapping("/{id}/model-inputs") public Map<String,Object> inputs(@AuthenticationPrincipal Identity node,@PathVariable String id,@RequestParam long epoch) {return model.inputs(node,id,epoch);}
    @GetMapping("/{id}/artifacts") public Map<String,Object> attachments(@AuthenticationPrincipal Identity node,@PathVariable String id,@RequestParam long epoch,@RequestParam(defaultValue="") String afterId,@RequestParam(defaultValue="0") int afterVersion,@RequestParam(defaultValue="20") int limit) {return model.attachments(node,id,epoch,afterId,afterVersion,limit);}
    @GetMapping("/{id}/artifacts/{artifact}") public Map<String,Object> attachment(@AuthenticationPrincipal Identity node,@PathVariable String id,@PathVariable String artifact,@RequestParam long epoch,@RequestParam int version) {return model.attachment(node,id,epoch,artifact,version);}
    @PostMapping("/{id}/model-failure") public Map<String,Object> failure(@AuthenticationPrincipal Identity node,@PathVariable String id,@Valid @RequestBody Requests.ModelFailure body) {return control.modelFailure(node,id,body);}
    @GetMapping("/{id}/tools") public List<Map<String,Object>> tools(@AuthenticationPrincipal Identity node,@PathVariable String id,@RequestParam long epoch) {return model.catalog(node,id,epoch);}
    @PostMapping("/{id}/model-output") public Map<String,Object> output(@AuthenticationPrincipal Identity node,@PathVariable String id,@Valid @RequestBody Requests.ModelResult body) {return model.output(node,id,body);}
}
