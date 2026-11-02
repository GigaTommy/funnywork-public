package com.funnywork;

import java.util.*;
import java.util.regex.Pattern;

/** Direct protected-change requests are held even when a role model fails to flag them. */
final class MaintenanceIntakePolicy {
    private MaintenanceIntakePolicy() {}
    private static final Pattern PROTECTED=Pattern.compile("(?im)(?:^|[\\n。；;])\\s*(?:(?:请|麻烦|帮我|直接|务必|要求|期望[:：]?)\\s*)*(?:"
        +"(?:删除|删掉|移除|停掉|关闭|禁用)\\s*(?:(?:所有|整个|全部|的)\\s*)*(?:后台服务|管理后台|管理后端|认证服务|业务后端|services/(?:business|identity))"
        +"|(?:取消|移除|关闭|禁用|绕过)\\s*(?:(?:所有|全部|对|的)\\s*)*(?:token|tokens|令牌|配额|额度|计费|限额|认证|鉴权|权限校验)"
        +"|(?:不限|无限)(?:制|量)?\\s*(?:token|tokens|令牌|额度)"
        +"|(?:delete|remove|disable|shut down)\\s+(?:(?:the|all|entire)\\s+)*(?:admin(?:istration)?\\s+(?:backend|service)|backend\\s+(?:service|files)|services/(?:business|identity))"
        +"|(?:remove|disable|bypass)\\s+(?:all\\s+)?(?:token\\s+(?:limits?|quotas?)|authentication|authorization)"
        +"|(?:unlimited|no)\\s+token\\s+(?:usage|limits?|quotas?))");
    static Optional<String> protectedRequest(String body,Object requirement){return protectedRequest(body,requirement,List.of());}
    static Optional<String> protectedRequest(String body,Object requirement,List<?> fields){
        var texts=new ArrayList<String>();
        for(Object value:fields)if(value instanceof Map<?,?> field&&field.get("expected") instanceof String text)texts.add(text);
        if(texts.isEmpty())texts.add(body);
        if(requirement instanceof Map<?,?> context&&context.get("spec") instanceof Map<?,?> spec){texts.add(String.valueOf(spec.get("requirement")));if(spec.get("criteria") instanceof List<?> criteria)for(Object c:criteria)texts.add(String.valueOf(((Map<?,?>)c).get("expected")));}
        for(String text:texts){var match=PROTECTED.matcher(text);if(match.find())return Optional.of(match.group().strip());}return Optional.empty();
    }
    private static final Pattern BASELINE=Pattern.compile("(?iu)基线|\\bbaseline\\b");
    private static final Pattern CANDIDATE=Pattern.compile("(?iu)候选|修复后|修改后|\\bcandidate\\b|\\bafter (?:the )?(?:fix|change)\\b");
    private static final Pattern TEST=Pattern.compile("(?iu)\\b(?:sat|uat|tests?)\\b|测试|检查");
    private static final Pattern NEGATIVE_PASS=Pattern.compile("(?iu)(?:\\bnot\\s+(?:(?:required|expected)\\s+to\\s+)?|\\bnever\\s+|\\bneed\\s+not\\s+)(?:pass(?:ed|es)?|succeed(?:ed|s)?|exit(?:\\s*code)?\\s*[:=]?\\s*0)\\b|(?:不|未)(?:应该|要求|应|能|能够|曾|要)?(?:通过|成功|退出码\\s*(?:为|是|=)?\\s*0)|(?:无需|无须|不必)(?:通过|成功)");
    private static final Pattern PASS=Pattern.compile("(?iu)\\b(?:pass(?:ed|es)?|succeed(?:ed|s)?|success(?:ful(?:ly)?)?)\\b|(?<!不)(?<!未)通过|退出码\\s*(?:为|是|=)?\\s*0|exit(?:\\s*code)?\\s*[:=]?\\s*0");
    /** Reject a generated baseline-pass condition that no structured client expectation requests. */
    static boolean unsupportedBaselinePass(com.fasterxml.jackson.databind.JsonNode product,List<?> feedbacks){
        var expected=new ArrayList<String>();
        for(Object value:feedbacks)if(value instanceof Map<?,?> field&&!Boolean.TRUE.equals(field.get("legacyUnstructured"))&&field.get("expected") instanceof String text)expected.add(text);
        if(expected.isEmpty()||expected.stream().anyMatch(MaintenanceIntakePolicy::baselinePass))return false;
        if(baselinePass(product.path("requirement").asText())||baselinePass(product.path("acceptance").asText()))return true;
        for(var criterion:product.path("acceptanceCriteria"))if(baselinePass(criterion.path("expected").asText()))return true;
        return false;
    }
    private static boolean baselinePass(String text){
        for(String clause:text.split("[。；;\\n]")){
            var baseline=BASELINE.matcher(clause);if(!baseline.find())continue;
            var candidate=CANDIDATE.matcher(clause);
            if(candidate.find()){
                clause=candidate.start()<baseline.start()?clause.substring(baseline.start()):clause.substring(0,candidate.start());
            }
            if(TEST.matcher(clause).find()&&PASS.matcher(NEGATIVE_PASS.matcher(clause).replaceAll(" ")).find())return true;
        }
        return false;
    }
    static boolean containsEvidence(Object payload,String quote){if(payload instanceof String text)return text.contains(quote);if(payload instanceof Map<?,?> values)return values.values().stream().anyMatch(v->containsEvidence(v,quote));if(payload instanceof List<?> values)return values.stream().anyMatch(v->containsEvidence(v,quote));return false;}
    static void candidate(com.fasterxml.jackson.databind.JsonNode candidate){
        for(var file:candidate.path("files")){String path=file.path("filename").asText(),old=file.path("previous_filename").asText();
            if(("removed".equals(file.path("status").asText())&&core(path))||("renamed".equals(file.path("status").asText())&&core(old)&&!core(path)))throw ApiException.conflict("MAINTENANCE_PROTECTED_BACKEND_REMOVAL");
        }
    }
    private static boolean core(String path){return path.startsWith("services/business/")||path.startsWith("services/identity/");}
}
