package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 普通陪伴 Agent。
 *
 * <p>处理学习、生活、编程、校园事务等普通聊天，不生成后台心理报告。</p>
 */
@Component
public class CompanionAgent implements MindBridgeAgent {

    private final AiClient aiClient;

    public CompanionAgent(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public AgentName name() {
        return AgentName.COMPANION_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.intentRouted()
                && context.intent() == IntentType.CHAT
                && !context.responsePlanned();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        context.setRiskLevel(RiskLevel.LOW);
        context.setResponseAgent(AgentName.COMPANION_AGENT);
        String plan = planResponse(context);
        context.setResponsePlan(plan);
        context.setResponseMessages(buildResponseMessages(context, plan));
        context.markResponsePlanned();
        return AgentDecision.finish(AgentAction.PLAN_RESPONSE, "normal companion response planned by model");
    }

    private String planResponse(AgentContext context) {
        try {
            String plan = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 CompanionAgent。
                            你负责普通学习、生活、校园事务、编程和日常聊天。
                            请根据当前输入和记忆摘要，制定一句简短回复策略。
                            不要做心理评估，不要输出风险等级，不要替用户下诊断。
                            """ + PromptTemplates.RETRIEVAL_SAFETY_RULES),
                    AiMessage.user("""
                            记忆摘要：
                            %s

                            当前输入：
                            %s
                            """.formatted(context.memoryBrief(), context.modelInput()))
            )).trim();
            return plan.isBlank() ? "围绕用户当前问题直接、自然地回答。" : shorten(plan, 300);
        } catch (Exception ignored) {
            return "围绕用户当前问题直接、自然地回答。";
        }
    }

    private List<AiMessage> buildResponseMessages(AgentContext context, String plan) {
        List<AiMessage> messages = new ArrayList<>();
        messages.add(PromptTemplates.answerSystemPrompt(
                IntentType.CHAT,
                RiskLevel.LOW,
                context.user().getDisplayName()));
        messages.add(AiMessage.user("""
                当前由 CompanionAgent 负责回复。
                记忆摘要：
                %s

                回复策略：
                %s
                """.formatted(context.memoryBrief(), plan)));
        messages.addAll(context.modelHistory());
        return messages;
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
