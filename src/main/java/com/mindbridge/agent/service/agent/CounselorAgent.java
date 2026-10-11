package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.knowledge.KnowledgeEvidence;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 心理支持 Agent。
 *
 * <p>处理咨询和风险场景，回复会结合 RAG 知识与 RiskGuardian 的后台评估结果。</p>
 */
@Component
public class CounselorAgent implements MindBridgeAgent {

    private final AiClient aiClient;

    public CounselorAgent(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public AgentName name() {
        return AgentName.COUNSELOR_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.riskAssessed()
                && context.intent() != IntentType.CHAT
                && !context.responsePlanned();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        context.setResponseAgent(AgentName.COUNSELOR_AGENT);
        String plan = planResponse(context);
        context.setResponsePlan(plan);
        context.setResponseMessages(buildResponseMessages(context, plan));
        context.markResponsePlanned();
        return AgentDecision.finish(
                AgentAction.PLAN_RESPONSE,
                "support response planned by model with risk=%s".formatted(context.riskLevel()));
    }

    private String planResponse(AgentContext context) {
        try {
            String plan = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 CounselorAgent。
                            你负责心理支持式回应策略，不直接给诊断。
                            请结合记忆摘要、风险守护结果和知识库命中，制定 2-3 句回复策略。
                            高风险时必须优先保护学生安全。
                            不要输出后台标签、风险等级、分数或报告口吻。
                            """),
                    AiMessage.user("""
                            记忆摘要：
                            %s

                            当前输入：
                            %s

                            风险守护结果：
                            %s

                            知识库 query：
                            %s

                            知识库命中：
                            %s
                            """.formatted(
                            context.memoryBrief(),
                            context.modelInput(),
                            context.assessment() == null ? "无" : context.assessment().summary(),
                            context.knowledgeQuery(),
                            formatKnowledge(context)))
            )).trim();
            return plan.isBlank() ? "先共情，再给出具体支持步骤；高风险时优先安全。" : shorten(plan, 500);
        } catch (Exception ignored) {
            return "先共情，再给出具体支持步骤；高风险时优先安全。";
        }
    }

    private List<AiMessage> buildResponseMessages(AgentContext context, String plan) {
        String knowledgeContext = KnowledgeEvidence.format(context.retrievedKnowledge());
        List<AiMessage> messages = new ArrayList<>();
        messages.add(PromptTemplates.answerSystemPrompt(
                context.intent(),
                context.riskLevel(),
                knowledgeContext,
                context.user().getDisplayName()));
        messages.add(AiMessage.system("""
                当前由 CounselorAgent 负责回复。
                记忆摘要：
                %s

                KnowledgeAgent 检索 query：
                %s

                回复策略：
                %s
                """.formatted(context.memoryBrief(), context.knowledgeQuery(), plan)));
        messages.addAll(context.modelHistory());
        return messages;
    }

    private String formatKnowledge(AgentContext context) {
        if (context.retrievedKnowledge().isEmpty()) {
            return "无";
        }
        return String.join("\n", context.retrievedKnowledge().stream()
                .limit(4)
                .map(result -> "- " + result.content())
                .toList());
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
