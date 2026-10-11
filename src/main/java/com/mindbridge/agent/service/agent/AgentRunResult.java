package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.List;

/**
 * Agent loop 完成后的结构化结果。
 */
public record AgentRunResult(
        IntentType intent,
        RiskLevel riskLevel,
        PsychologyAssessment assessment,
        List<SearchResult> retrievedKnowledge,
        List<AiMessage> modelHistory,
        List<AiMessage> responseMessages,
        String memoryBrief,
        String knowledgeQuery,
        String responsePlan,
        AgentName responseAgent,
        List<AgentStep> steps,
        boolean knowledgeSufficient
) {
    public static AgentRunResult from(AgentContext context) {
        return new AgentRunResult(
                context.intent(),
                context.riskLevel(),
                context.assessment(),
                context.retrievedKnowledge(),
                context.modelHistory(),
                context.responseMessages(),
                context.memoryBrief(),
                context.knowledgeQuery(),
                context.responsePlan(),
                context.responseAgent(),
                context.steps(),
                context.knowledgeSufficient()
        );
    }

    public boolean requiresReport() {
        return intent != null && intent != IntentType.CHAT && assessment != null;
    }
}
