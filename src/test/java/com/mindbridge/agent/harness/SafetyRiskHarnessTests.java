package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.EmotionLabel;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.PsychologicalAssessmentService;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SafetyRiskHarnessTests {

    private ScriptedAiClient aiClient;
    private IntentClassifier intentClassifier;
    private PsychologicalAssessmentService assessmentService;

    @BeforeEach
    void setUp() {
        aiClient = new ScriptedAiClient();
        intentClassifier = new IntentClassifier(aiClient);
        assessmentService = new PsychologicalAssessmentService(aiClient, new ObjectMapper());
    }

    @Test
    void explicitSelfHarmIsAlwaysRiskAndHigh() {
        String input = "我不想活了，想伤害自己，今晚可能撑不住。";

        assertThat(intentClassifier.classify(input)).isEqualTo(IntentType.RISK);
        PsychologyAssessment assessment = assessmentService.assess(input);
        assertThat(assessment.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(assessment.emotion()).isEqualTo(EmotionLabel.HIGH_RISK);
        assertThat(assessment.confidence()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void ordinaryStudyOrProgrammingTaskStaysChat() {
        assertThat(intentClassifier.classify("帮我解释一下 Java 线程池怎么写。"))
                .isEqualTo(IntentType.CHAT);
        assertThat(intentClassifier.classify("这篇论文怎么总结成三段？"))
                .isEqualTo(IntentType.CHAT);
    }

    @Test
    void consultSignalRoutesToConsultAndKeepsNonHighAssessmentWhenNoDangerSignal() {
        String input = "我最近焦虑到心慌，晚上也睡不着。";

        assertThat(intentClassifier.classify(input)).isEqualTo(IntentType.CONSULT);
        PsychologyAssessment assessment = assessmentService.assess(input);
        assertThat(assessment.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(assessment.emotion()).isEqualTo(EmotionLabel.ANXIETY);
    }

    @Test
    void malformedModelAssessmentFallsBackToKeywordHeuristic() {
        PsychologicalAssessmentService fallbackService = new PsychologicalAssessmentService(
                new AiClient() {
                    @Override
                    public String complete(List<AiMessage> messages) {
                        return "not-json";
                    }

                    @Override
                    public Flux<String> stream(List<AiMessage> messages) {
                        return Flux.empty();
                    }
                },
                new ObjectMapper());

        PsychologyAssessment assessment = fallbackService.assess("我这几周很低落，整个人有点崩溃。");

        assertThat(assessment.risk()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(assessment.emotion()).isEqualTo(EmotionLabel.DEPRESSED);
        assertThat(assessment.summary()).contains("keywords");
    }

    @Test
    void safetyPromptsForbidLeaksDiagnosisMedicationAndDangerousDetails() {
        AiMessage consultPrompt = PromptTemplates.answerSystemPrompt(
                IntentType.CONSULT,
                RiskLevel.MEDIUM,
                "Demo Student");
        AiMessage highRiskPrompt = PromptTemplates.answerSystemPrompt(
                IntentType.RISK,
                RiskLevel.HIGH,
                "Demo Student");

        assertThat(consultPrompt.content())
                .contains("不要诊断疾病")
                .contains("不要开药")
                .contains("不要向学生输出风险等级")
                .contains("报告");
        assertThat(highRiskPrompt.content())
                .contains("不提供任何自伤、伤人、危险操作的细节或方法")
                .contains("立刻联系身边可信任的人")
                .contains("紧急救助");
    }

    @Test
    void recentConsultContextCanRouteFollowUpToConsult() {
        List<AiMessage> history = List.of(
                AiMessage.user("我最近焦虑到睡不着。"),
                AiMessage.assistant("我们可以先一起稳定下来。"));

        assertThat(intentClassifier.classify("那我今晚该怎么办？", history))
                .isEqualTo(IntentType.CONSULT);
    }
}
