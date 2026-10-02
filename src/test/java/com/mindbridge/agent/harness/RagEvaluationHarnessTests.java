package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.PsychologicalAssessmentService;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.knowledge.eval.RagEndToEndCaseResult;
import com.mindbridge.agent.service.knowledge.eval.RagEvalReport;
import com.mindbridge.agent.service.knowledge.eval.RagEvaluationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RagEvaluationHarnessTests {

    private RagEvaluationService evaluationService;
    private MindBridgeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MindBridgeProperties();
        ScriptedAiClient aiClient = new ScriptedAiClient();
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(knowledgeService.retrieve(anyString(), anyInt())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0, String.class);
            if (question.contains("不想活") || question.contains("伤害自己")) {
                return List.of(new SearchResult(
                        1L,
                        "risk-policy.md",
                        "HIGH immediate danger: 请把安全放在第一位，联系可信任的人、辅导员、学校心理中心或紧急救助。",
                        0.98));
            }
            return List.of(new SearchResult(
                    2L,
                    "campus-mental-health.md",
                    "焦虑心慌可使用 grounding 五感着陆、breathing 呼吸、sleep 睡眠和 routine 作息支持。",
                    0.91));
        });

        evaluationService = new RagEvaluationService(
                knowledgeService,
                properties,
                aiClient,
                new IntentClassifier(aiClient),
                new PsychologicalAssessmentService(aiClient, new ObjectMapper()),
                new ObjectMapper());
    }

    @Test
    void evaluatesRagDatasetWithIntentRiskRetrievalAndAnswerAssertions() {
        RagEvalReport report = evaluationService.evaluate("classpath:harness/rag-harness-scenarios.json", 2);

        assertThat(report.totalCases()).isEqualTo(2);
        assertThat(report.passedCases()).isEqualTo(2);
        assertThat(report.failedCases()).isZero();
        assertThat(report.cases()).allSatisfy(testCase -> {
            assertThat(testCase.passed()).isTrue();
            assertThat(testCase.failures()).isEmpty();
            assertThat(testCase.actualIntent()).isNotBlank();
            assertThat(testCase.actualRiskLevel()).isNotBlank();
            assertThat(testCase.retrievedSources()).isNotEmpty();
            assertThat(testCase.answer()).isNotBlank();
        });
    }

    @Test
    void summaryIncludesHarnessPassFailCounts() {
        RagEvalReport report = evaluationService.evaluate("classpath:harness/rag-harness-scenarios.json", 2);

        assertThat(evaluationService.formatSummary(report))
                .contains("passed=2")
                .contains("failed=0")
                .contains("Java harness assertions");
        assertThat(report.cases())
                .extracting(RagEndToEndCaseResult::expectedIntent)
                .containsExactly("RISK", "CONSULT");
    }

    @Test
    void recordsFusionStrategyForComparableEvaluationRuns() {
        RagEvalReport rrf = evaluationService.evaluate("classpath:harness/rag-harness-scenarios.json", 2);
        assertThat(rrf.fusionStrategy()).isEqualTo("RRF");
        assertThat(evaluationService.formatSummary(rrf)).contains("fusionStrategy=RRF");

        properties.getKnowledge().setFusionStrategy(MindBridgeProperties.Knowledge.FusionStrategy.WEIGHTED);
        RagEvalReport weighted = evaluationService.evaluate("classpath:harness/rag-harness-scenarios.json", 2);
        assertThat(weighted.fusionStrategy()).isEqualTo("WEIGHTED");
    }
}
