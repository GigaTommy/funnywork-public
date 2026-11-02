package com.funnywork;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded dependency closure. Source observations and declared dependencies remain distinguishable. */
final class MaintenanceImpact {
    private MaintenanceImpact() {}
    private static final Pattern IMPORT=Pattern.compile("(?:from\\s*|import\\s*|require\\s*\\(\\s*)['\"](\\.[^'\"]+)['\"]");
    private static String component(String path){var parts=path.split("/");return parts.length>1&&Set.of("apps","services","packages").contains(parts[0])?parts[0]+"/"+parts[1]:parts[0];}
    private static Set<String> owners(String path,Map<String,Map<String,Object>> components){var found=new LinkedHashSet<String>();components.forEach((key,c)->{for(Object prefix:(List<?>)c.get("paths"))if(MaintenanceRequirements.inside(path,String.valueOf(prefix)))found.add(key);});return found;}
    private static String resolve(String file,String relative){var parts=new ArrayDeque<String>();String path=file.contains("/")?file.substring(0,file.lastIndexOf('/')+1)+relative:relative;for(String segment:path.split("/")){if(segment.isEmpty()||segment.equals("."))continue;if(segment.equals("..")){if(parts.isEmpty())return "";parts.removeLast();}else parts.addLast(segment);}return String.join("/",parts);}
    static Map<String,Object> analyze(Map<String,Object> baseline,List<String> requested,List<?> declared){
        String sha=String.valueOf(baseline.get("baseSha"));if(!sha.matches("[a-f0-9]{40}|[a-f0-9]{64}"))throw ApiException.conflict("MAINTENANCE_BASE_INVALID");
        var files=(List<?>)baseline.getOrDefault("files",List.of());var snippets=(List<?>)baseline.getOrDefault("sourceSnippets",List.of());
        var components=new LinkedHashMap<String,Map<String,Object>>();for(Object raw:declared){var c=(Map<?,?>)raw;components.put((String)c.get("key"),new LinkedHashMap<>(Map.of("name",c.get("name"),"paths",c.get("paths"),"platforms",c.get("platforms"),"dependsOn",c.get("dependsOn"),"origin","CONFIGURED")));}
        var inventory=new LinkedHashSet<String>();for(Object raw:files){var file=(Map<?,?>)raw;String path=String.valueOf(file.get("path"));if(MaintenanceRemote.sensitive(path))continue;inventory.add(path);if(owners(path,components).isEmpty()){String key="observed:"+component(path);components.putIfAbsent(key,new LinkedHashMap<>(Map.of("name",component(path),"paths",List.of(component(path)),"platforms",List.of("unknown"),"dependsOn",List.of(),"origin","INVENTORY")));}}
        var edges=new ArrayList<Map<String,Object>>();components.forEach((key,c)->{for(Object dependency:(List<?>)c.get("dependsOn"))edges.add(Map.of("consumer",key,"dependency",dependency,"kind","CONFIGURED_DEPENDENCY"));});
        int observed=0;boolean edgeLimit=false;sourceImports:for(Object raw:snippets){var snippet=(Map<?,?>)raw;String file=String.valueOf(snippet.get("path"));if(!inventory.contains(file))continue;observed++;var matcher=IMPORT.matcher(String.valueOf(snippet.get("content")));int count=0;
            while(matcher.find()&&count++<200){String resolved=resolve(file,matcher.group(1));var candidates=new LinkedHashSet<String>(List.of(resolved,resolved+".ts",resolved+".tsx",resolved+".js",resolved+".vue",resolved+"/index.ts"));if(resolved.endsWith(".js"))candidates.add(resolved.substring(0,resolved.length()-3)+".ts");
                for(String target:candidates)if(inventory.contains(target))for(String consumer:owners(file,components))for(String dependency:owners(target,components))if(!consumer.equals(dependency)){if(edges.size()>=8000){edgeLimit=true;break sourceImports;}edges.add(Map.of("consumer",consumer,"dependency",dependency,"kind","SOURCE_IMPORT","file",file,"target",target));}
            }
        }
        var impacted=new LinkedHashMap<String,Map<String,Object>>();var unmatched=new ArrayList<String>();
        for(String path:requested){boolean found=false;for(String file:inventory)if(MaintenanceRequirements.inside(file,path))found=true;if(!found)unmatched.add(path);
            for(var e:components.entrySet())for(Object raw:(List<?>)e.getValue().get("paths")){String prefix=String.valueOf(raw);if(MaintenanceRequirements.inside(path,prefix)||MaintenanceRequirements.inside(prefix,path))impacted.putIfAbsent(e.getKey(),Map.of("key",e.getKey(),"name",e.getValue().get("name"),"platforms",e.getValue().get("platforms"),"reason","PATH_MATCH","path",path));}
        }
        boolean changed=true;while(changed){changed=false;for(var edge:edges){String consumer=String.valueOf(edge.get("consumer"));if(impacted.containsKey(edge.get("dependency"))&&!impacted.containsKey(consumer)&&components.containsKey(consumer)){var c=components.get(consumer);impacted.put(consumer,Map.of("key",consumer,"name",c.get("name"),"platforms",c.get("platforms"),"reason",edge.get("kind"),"via",edge.get("dependency")));changed=true;}}}
        var warnings=new ArrayList<String>();warnings.add("BOUNDED_SOURCE_SAMPLE");if(edgeLimit)warnings.add("DEPENDENCY_EDGE_LIMIT");warnings.add("RUNTIME_AND_DYNAMIC_DEPENDENCIES_UNVERIFIED");if(declared.isEmpty())warnings.add("NO_DECLARED_COMPONENT_MAP");if(!unmatched.isEmpty())warnings.add("REQUESTED_PATHS_NOT_IN_INVENTORY");
        var result=new LinkedHashMap<String,Object>();result.put("baseSha",sha);result.put("requestedPaths",requested);result.put("components",new ArrayList<>(impacted.values()));result.put("edges",edges);result.put("unmatchedPaths",unmatched);result.put("warnings",warnings);result.put("coverage",Map.of("inventoryFiles",inventory.size(),"sampledFiles",observed,"complete",false));return result;
    }
}
