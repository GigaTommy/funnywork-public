package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceProductConstraintsTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private boolean rejected(String criterion,String expected){return MaintenanceIntakePolicy.unsupportedBaselinePass(mapper.valueToTree(Map.of("acceptanceCriteria",List.of(Map.of("expected",criterion)))),List.of(Map.of("expected",expected)));}
    @ParameterizedTest @ValueSource(strings={"在基线 df794d7 上 SAT 与 UAT 均以退出码 0 通过", "SAT tests must pass on baseline df794d7", "Baseline UAT succeeds with exit code 0"})
    void generatedBaselineSuccessIsNotDerivedFromActualReproduction(String criterion){assertTrue(rejected(criterion,"修复候选显示正确的回执"));}
    @ParameterizedTest @ValueSource(strings={"基线 SAT 失败，候选 SAT 通过", "Baseline tests must not pass; candidate tests must pass", "基线测试不应该通过；候选测试通过", "候选测试通过且保留基线记录", "ACCEPTED 显示已通过验收，但尚未发布或安装"})
    void negativeBaselineOrExplicitCandidateTargetsRemainValid(String criterion){assertFalse(rejected(criterion,"修复候选显示正确的回执"));}
    @Test void explicitBaselineInvariantIsPreserved(){assertFalse(rejected("基线兼容检查通过", "基线兼容检查通过，候选输出正确"));}
    @Test void negativeSourceStatementCannotAuthorizeTheOppositeCondition(){assertTrue(rejected("Baseline tests must pass", "Baseline tests must not pass; candidate tests pass"));assertTrue(rejected("基线测试通过", "基线测试不应该通过，候选测试通过"));}
    @Test void legacyUnstructuredSourcesAreNotTreatedAsStructuredExpectedFields(){assertFalse(MaintenanceIntakePolicy.unsupportedBaselinePass(mapper.valueToTree(Map.of("acceptance","基线 SAT 测试通过")),List.of(Map.of("expected","历史自由文本","legacyUnstructured",true))));}
    @ParameterizedTest @ValueSource(strings={"基线测试无需通过", "基线测试不要求退出码 0", "Baseline tests are not required to pass", "Baseline tests must not exit code 0"})
    void explicitAbsenceOfBaselineSuccessIsNotRejected(String criterion){assertFalse(rejected(criterion,"修复候选显示正确的回执"));}
    @Test void candidateClauseCannotHideAnUnsupportedBaselineCondition(){assertTrue(rejected("Candidate tests pass, baseline tests must pass", "修复候选显示正确的回执"));}
    @Test void generatedRequirementBodyCannotHideAnUnsupportedBaselineCondition(){assertTrue(MaintenanceIntakePolicy.unsupportedBaselinePass(mapper.valueToTree(Map.of("requirement","基线 SAT 测试必须通过","acceptance","候选输出正确")),List.of(Map.of("expected","修复候选显示正确的回执"))));}
}
