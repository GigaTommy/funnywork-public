package com.funnywork;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixed initial dependency graph. Changes to running plans require a separate version protocol. */
final class TaskDependencies {
    private TaskDependencies() {}
    static List<Requests.Dependency> validate(int size,List<Requests.Dependency> edges) {
        if(size<1 || size>100 || edges.size()>1000)throw invalid();
        var outgoing=new ArrayList<List<Integer>>();int[] degree=new int[size];
        for(int i=0;i<size;i++)outgoing.add(new ArrayList<>());
        var seen=new HashSet<String>();
        for(var edge:edges) {
            if(edge==null || edge.predecessor()==null || edge.successor()==null || edge.predecessor()<0 || edge.predecessor()>=size || edge.successor()<0 || edge.successor()>=size
                    || edge.predecessor().equals(edge.successor()) || !seen.add(edge.predecessor()+":"+edge.successor()))throw invalid();
            outgoing.get(edge.predecessor()).add(edge.successor());degree[edge.successor()]++;
        }
        var ready=new ArrayDeque<Integer>();for(int i=0;i<size;i++)if(degree[i]==0)ready.add(i);
        int visited=0;
        while(!ready.isEmpty()) {int current=ready.remove();visited++;for(int next:outgoing.get(current))if(--degree[next]==0)ready.add(next);}
        if(visited!=size)throw invalid();
        return edges.stream().sorted(Comparator.comparingInt(Requests.Dependency::predecessor).thenComparingInt(Requests.Dependency::successor)).toList();
    }
    static List<Map<String,Object>> canonical(List<Requests.Dependency> edges) {
        return edges.stream().map(edge->{
            Map<String,Object> value=new LinkedHashMap<>();value.put("predecessor",edge.predecessor());value.put("successor",edge.successor());
            if(edge.completion()!=Requests.DependencyCompletion.SUCCEEDED)value.put("completion",edge.completion().name());
            return value;
        }).toList();
    }
    static void capability(String capability,List<Requests.Dependency> edges) {
        if(edges.isEmpty())return;
        var required=TextStats.CAPABILITY.equals(capability)?Requests.DependencyCompletion.SUCCEEDED:
            "model.read.v1".equals(capability)?Requests.DependencyCompletion.OUTPUT_AVAILABLE:null;
        if(required==null || edges.stream().anyMatch(edge->edge.completion()!=required))
            throw new ApiException(400,"UNSUPPORTED_DEPENDENCY_CAPABILITY","Dependency completion must match the execution capability");
    }
    private static ApiException invalid(){return new ApiException(400,"INVALID_DEPENDENCY_GRAPH","Dependencies must form a bounded, distinct acyclic graph over input ordinals");}

    static void advance(JdbcTemplate db,String task) {
        // Called only under the tenant transaction lock. Waiting units hold no attempt/resources.
        var edges=db.queryForList("select predecessor,successor,completion from task_dependencies where task_id=?",task);
        if(edges.isEmpty())return;
        var rows=db.queryForList("select ordinal,state from work_units where task_id=? order by ordinal",task);
        int size=rows.size();var states=new String[size];var incoming=new ArrayList<List<Requests.Dependency>>();var outgoing=new ArrayList<List<Integer>>();
        int[] degree=new int[size];
        for(int i=0;i<size;i++) {states[i]=(String)rows.get(i).get("state");incoming.add(new ArrayList<>());outgoing.add(new ArrayList<>());}
        for(var edge:edges) {
            int from=((Number)edge.get("predecessor")).intValue(),to=((Number)edge.get("successor")).intValue();
            incoming.get(to).add(new Requests.Dependency(from,to,Requests.DependencyCompletion.valueOf((String)edge.get("completion"))));outgoing.get(from).add(to);degree[to]++;
        }
        var ready=new ArrayDeque<Integer>();for(int i=0;i<size;i++)if(degree[i]==0)ready.add(i);
        var updates=new ArrayList<Object[]>();var failed=Set.of("FAILED","CANCELLED","BLOCKED_AUTH","BLOCKED_DEPENDENCY");
        while(!ready.isEmpty()) {
            int current=ready.remove();
            if("WAITING_DEPENDENCY".equals(states[current])) {
                boolean blocked=false,success=true;
                for(var edge:incoming.get(current)) {String state=states[edge.predecessor()];blocked|=failed.contains(state);success&=(edge.completion()==Requests.DependencyCompletion.OUTPUT_AVAILABLE?"GENERATED":"SUCCEEDED").equals(state);}
                if(blocked || success) {
                    states[current]=blocked?"BLOCKED_DEPENDENCY":"READY";
                    updates.add(new Object[]{states[current],task,current});
                }
            }
            for(int next:outgoing.get(current))if(--degree[next]==0)ready.add(next);
        }
        if(!updates.isEmpty())db.batchUpdate("update work_units set state=?,next_dispatch_at=0 where task_id=? and ordinal=? and state='WAITING_DEPENDENCY'",updates);
    }
}
