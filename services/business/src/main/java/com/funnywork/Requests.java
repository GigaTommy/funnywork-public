package com.funnywork;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import com.funnywork.ArtifactService.Attachment;
import java.util.*;

public final class Requests {
    public record User(@NotBlank @Size(max=120) String name,@NotEmpty Set<@Pattern(regexp="ADMIN|OPERATOR|VIEWER|RECONCILER") String> roles,@NotEmpty Set<@Pattern(regexp="[a-z0-9-]{1,64}") String> zones) {}
    public record Node(@NotBlank @Size(max=120) String name,@Pattern(regexp="[a-z0-9-]{1,64}") @NotNull String zone,@Min(1) @Max(16) int capacity,@Pattern(regexp=Capabilities.PATTERN) String capability) {
        public Node {if(capability==null)capability=TextStats.CAPABILITY;}
        public Node(String name,String zone,int capacity) {this(name,zone,capacity,TextStats.CAPABILITY);}
    }
    public record Agent(@Pattern(regexp="[a-z0-9-]{1,100}") @NotNull String name,@Pattern(regexp="[0-9]+\\.[0-9]+\\.[0-9]+") @NotNull String version,@Pattern(regexp=Capabilities.PATTERN) String capability) {
        public Agent {if(capability==null)capability=TextStats.CAPABILITY;}
        public Agent(String name,String version) {this(name,version,TextStats.CAPABILITY);}
    }
    public record Task(@NotBlank @Size(max=64) String agentId,@Pattern(regexp="[a-z0-9-]{1,64}") @NotNull String zone,@NotEmpty @Size(max=100) List<@NotNull @Size(max=10000) String> inputs,
                       @Size(max=20) List<@NotNull @Valid Attachment> attachments,
                       @Size(max=1000) List<@NotNull @Valid Dependency> dependencies, boolean dispatchPaused, @Size(max=160) String title) {
        public Task {title=title==null||title.isBlank()?null:title.strip();attachments=attachments==null?List.of():List.copyOf(attachments);dependencies=dependencies==null?List.of():List.copyOf(dependencies);}
        public Task(String agentId,String zone,List<String> inputs,List<Attachment> attachments,List<Dependency> dependencies,boolean dispatchPaused) {this(agentId,zone,inputs,attachments,dependencies,dispatchPaused,null);}
        public Task(String agentId,String zone,List<String> inputs,List<Attachment> attachments,List<Dependency> dependencies) {this(agentId,zone,inputs,attachments,dependencies,false);}
        public Task(String agentId,String zone,List<String> inputs,List<Attachment> attachments) {this(agentId,zone,inputs,attachments,List.of());}
        public Task(String agentId,String zone,List<String> inputs) {this(agentId,zone,inputs,List.of());}
    }
    public enum DependencyCompletion { SUCCEEDED, OUTPUT_AVAILABLE }
    public record Dependency(@NotNull @Min(0) @Max(99) Integer predecessor,@NotNull @Min(0) @Max(99) Integer successor,DependencyCompletion completion) {
        public Dependency {completion=completion==null?DependencyCompletion.SUCCEEDED:completion;}
        public Dependency(Integer predecessor,Integer successor) {this(predecessor,successor,DependencyCompletion.SUCCEEDED);}
    }
    public record PlanAppend(@NotNull @Min(1) @Max(99) Integer expectedVersion,
                             @NotEmpty @Size(max=99) List<@NotNull @Size(max=10000) String> inputs,
                             @Size(max=1000) List<@NotNull @Valid Dependency> dependencies) {
        public PlanAppend {dependencies=dependencies==null?List.of():List.copyOf(dependencies);}
    }
    public enum AdmissionReason { READY, NOT_ACCEPTING, STOPPING, WARMING_UP, CPU_BUSY, MEMORY_LOW, DISK_LOW, POWER_UNAVAILABLE, TELEMETRY_UNAVAILABLE, ACTIVITY_UNAVAILABLE, USER_ACTIVE }
    public record NodeAdmission(@NotNull Boolean paused) {}
    public record NodeSource(@NotNull @Pattern(regexp="[A-Za-z0-9-]{1,64}") String executionPrincipalId,@NotNull @Pattern(regexp="[A-Za-z0-9._-]{1,120}") String sourceId,@NotNull @Pattern(regexp="[a-f0-9]{64}") String deploymentHash) {}
    public record NodeSources(@NotNull @Size(max=64) List<@NotNull @Valid NodeSource> sources) {}
    public record TaskAdmission(@NotNull Boolean paused) {}
    public record Heartbeat(@NotNull Boolean accepting, AdmissionReason reason) {}
    public record Result(@Min(1) long epoch,@Pattern(regexp="[a-f0-9]{64}") @NotNull String sha256,@Min(0) int codePoints,@Min(0) int lines) {
        public Map<String,Object> value() { return Map.of("sha256",sha256,"codePoints",codePoints,"lines",lines); }
    }
    public record ModelResult(@Min(1) long epoch,@NotNull @Size(max=100000) String text) {}
    public enum ModelFailureCode { RUNTIME_FAILED, BUDGET_EXCEEDED, OUTPUT_TOO_LARGE }
    public record ModelFailure(@Min(1) long epoch,@NotNull ModelFailureCode failureCode) {}
    public record Epoch(@Min(1) long epoch) {}
    public record AdmissionPolicy(@Min(1) @Max(1000000) int maxOutstandingUnits) {}
    public record SchedulingPolicy(@Min(1) @Max(10000) int maxConcurrency) {}
}
