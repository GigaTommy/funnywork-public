package com.funnywork;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/node-enrollments")
public class NodeEnrollmentController {
    private final NodeEnrollment service;
    public NodeEnrollmentController(NodeEnrollment service){this.service=service;}
    @GetMapping("/{id}") public Map<String,Object> status(@AuthenticationPrincipal Identity admin,@PathVariable String id){return service.status(admin,id);}
    @PostMapping public Map<String,Object> issue(@AuthenticationPrincipal Identity admin,@Valid @RequestBody NodeEnrollment.Issue request){return service.issue(admin,request);}
    @DeleteMapping("/{id}") public void revoke(@AuthenticationPrincipal Identity admin,@PathVariable String id){service.revoke(admin,id);}
    @PostMapping("/redeem") public Map<String,Object> redeem(@Valid @RequestBody NodeEnrollment.Redeem request){return service.redeem(request.token());}
}
