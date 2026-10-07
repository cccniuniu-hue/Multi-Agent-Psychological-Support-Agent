package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 知识库 Agent。
 *
 * <p>只有心理咨询和风险场景才检索 Chroma/RAG，普通学习闲聊不会被强行转成心理测评。</p>
 */
@Component
public class KnowledgeAgent implements MindBridgeAgent {

    private static final Duration QUERY_REWRITE_TIMEOUT = Duration.ofSeconds(3);

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
        int topK = properties.getKnowledge().getTopK();
        List<SearchResult> retrieved = knowledgeService.retrieve(original, topK);
        String query = original;
        if (isComplexConsultation(context) && !isKnowledgeEnough(context, retrieved)) {
            String rewritten = rewriteQuery(context);
            if (!rewritten.equals(original)) {
                List<SearchResult> second = knowledgeService.retrieve(rewritten, topK);
                retrieved = mergeResults(retrieved, second, topK);
                query = keepOriginal(original, rewritten);
            }
        }
        context.setKnowledgeQuery(query);
        context.setRetrievedKnowledge(retrieved);
        context.markKnowledgeHandled();
        return AgentDecision.continueWith(
                AgentAction.RETRIEVE_KNOWLEDGE,
                "query=%s; retrieved=%d".formatted(query, retrieved.size()));
    }

    private List<SearchResult> mergeResults(List<SearchResult> original, List<SearchResult> rewritten, int topK) {
        Map<Object, SearchResult> merged = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(original.size(), rewritten.size()); i++) {
            if (i < original.size()) {
                SearchResult result = original.get(i);
                merged.putIfAbsent(resultKey(result), result);
            }
            if (i < rewritten.size()) {
                SearchResult result = rewritten.get(i);
                merged.putIfAbsent(resultKey(result), result);
            }
        }
        return merged.values().stream().limit(topK).toList();
    }

    private Object resultKey(SearchResult result) {
        return result.chunkId() == null
                ? Arrays.asList(result.source(), result.content())
                : result.chunkId();
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
        return completeQueryRewrite(List.of(
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
            ), context.modelInput());
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
            ));
            return isSufficientAnswer(decision);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isSufficientAnswer(String decision) {
        return decision != null && "SUFFICIENT".equalsIgnoreCase(decision.trim());
    }

    private String completeQueryRewrite(List<AiMessage> messages, String fallback) {
        try {
            // ponytail: this bounds waiting; a non-interruptible upstream request may finish later.
            String answer = Mono.fromCallable(() -> aiClient.complete(messages))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block(QUERY_REWRITE_TIMEOUT);
            return normalizeQuery(answer, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
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
        if (value == null || value.contains("\n") || value.contains("\r")) {
            return fallback;
        }
        String query = value
                .replace("查询词：", "")
                .replace("query:", "")
                .replace("Query:", "")
                .trim();
        if (query.isBlank() || query.length() > 40) {
            return fallback;
        }
        return query;
    }
}
