package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 知识库 Agent。
 *
 * <p>只有心理咨询和风险场景才检索 Chroma/RAG，普通学习闲聊不会被强行转成心理测评。</p>
 */
@Component
public class KnowledgeAgent implements MindBridgeAgent {

    private final KnowledgeService knowledgeService;
    private final MindBridgeProperties properties;
    private final AiClient aiClient;

    public KnowledgeAgent(KnowledgeService knowledgeService, MindBridgeProperties properties, AiClient aiClient) {
        this.knowledgeService = knowledgeService;
        this.properties = properties;
        this.aiClient = aiClient;
    }

    @Override
    public AgentName name() {
        return AgentName.KNOWLEDGE_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.intentRouted()
                && !context.knowledgeHandled()
                && context.intent() != IntentType.CHAT;
    }

    @Override
    public AgentDecision act(AgentContext context) {
        String original = context.modelInput();
        boolean complexConsultation = isComplexConsultation(context);
        String query = complexConsultation ? keepOriginal(original, rewriteQuery(context)) : original;
        List<SearchResult> retrieved = knowledgeService.retrieve(query, properties.getKnowledge().getTopK());
        String observation = "query=%s; retrieved=%d".formatted(query, retrieved.size());
        if (complexConsultation && !isKnowledgeEnough(context, retrieved)) {
            String refinedQuery = keepOriginal(original, refineQuery(context, query, retrieved));
            if (!refinedQuery.equals(query)) {
                List<SearchResult> refined = knowledgeService.retrieve(refinedQuery, properties.getKnowledge().getTopK());
                if (!refined.isEmpty()) {
                    query = refinedQuery;
                    retrieved = refined;
                    observation = "query=%s; refined=true; retrieved=%d".formatted(query, retrieved.size());
                }
            }
        }
        context.setKnowledgeQuery(query);
        context.setRetrievedKnowledge(retrieved);
        context.markKnowledgeHandled();
        return AgentDecision.continueWith(
                AgentAction.RETRIEVE_KNOWLEDGE,
                observation);
    }

    private boolean isComplexConsultation(AgentContext context) {
        String input = context.modelInput();
        // ponytail: length and multiple clauses are a conservative proxy; revisit if real queries show misses.
        return context.intent() == IntentType.CONSULT && input != null && input.length() >= 40
                && (input.contains("，") || input.contains(",") || input.contains("。"));
    }

    private String keepOriginal(String original, String addition) {
        if (addition == null || addition.isBlank() || addition.equals(original)
                || addition.startsWith(original + " ")) {
            return addition == null || addition.isBlank() ? original : addition;
        }
        return original + " " + addition;
    }

    private String rewriteQuery(AgentContext context) {
        try {
            String query = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 KnowledgeAgent。
                            你的任务是把学生输入改写成适合检索校园心理知识库的中文查询词。
                            只输出查询词本身，不要解释，不要超过 40 个字。
                            聚焦心理支持、校园求助流程、风险处理或情绪调节知识。
                            """),
                    AiMessage.user("""
                            记忆摘要：
                            %s

                            当前输入：
                            %s
                            """.formatted(context.memoryBrief(), context.modelInput()))
            )).trim();
            return normalizeQuery(query, context.modelInput());
        } catch (Exception ignored) {
            return context.modelInput();
        }
    }

    private boolean isKnowledgeEnough(AgentContext context, List<SearchResult> results) {
        if (results.isEmpty()) {
            return false;
        }
        try {
            String decision = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 KnowledgeAgent。
                            判断检索结果是否足以支持后续心理关怀回答。
                            只输出 SUFFICIENT 或 INSUFFICIENT。
                            """),
                    AiMessage.user("""
                            当前输入：
                            %s

                            检索结果：
                            %s
                            """.formatted(context.modelInput(), formatResults(results)))
            )).trim().toUpperCase();
            return decision.contains("SUFFICIENT") && !decision.contains("INSUFFICIENT");
        } catch (Exception ignored) {
            return true;
        }
    }

    private String refineQuery(AgentContext context, String previousQuery, List<SearchResult> results) {
        try {
            String query = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 KnowledgeAgent。
                            上一次检索信息不足，请给出一个新的、更具体的中文检索 query。
                            只输出查询词本身，不要解释，不要超过 40 个字。
                            """),
                    AiMessage.user("""
                            当前输入：
                            %s

                            上一次 query：
                            %s

                            上一次结果：
                            %s
                            """.formatted(context.modelInput(), previousQuery, formatResults(results)))
            )).trim();
            return normalizeQuery(query, previousQuery);
        } catch (Exception ignored) {
            return previousQuery;
        }
    }

    private String formatResults(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "无";
        }
        return String.join("\n", results.stream()
                .limit(4)
                .map(result -> "- " + result.content())
                .toList());
    }

    private String normalizeQuery(String value, String fallback) {
        String query = value
                .replace("查询词：", "")
                .replace("query:", "")
                .replace("Query:", "")
                .replaceAll("[\\r\\n]+", " ")
                .trim();
        if (query.isBlank()) {
            return fallback;
        }
        return query.length() > 60 ? query.substring(0, 60) : query;
    }
}
