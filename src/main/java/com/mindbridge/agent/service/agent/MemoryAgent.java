package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.service.PrivacySanitizer;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.StageSummary;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 记忆 Agent。
 *
 * <p>优先读取 Redis 短期记忆和用户画像；短期记忆过期时，从 MySQL 长期聊天记录恢复最近上下文。</p>
 */
@Component
public class MemoryAgent implements MindBridgeAgent {

    private static final int SUMMARY_INTERVAL_TURNS = 5;

    private final ChatMessageRepository chatMessageRepository;
    private final ShortTermMemoryService shortTermMemoryService;
    private final MindBridgeProperties properties;
    private final PrivacySanitizer privacySanitizer;
    private final AiClient aiClient;
    private final UserProfileMemoryService userProfileMemoryService;

    public MemoryAgent(
            ChatMessageRepository chatMessageRepository,
            ShortTermMemoryService shortTermMemoryService,
            MindBridgeProperties properties,
            PrivacySanitizer privacySanitizer,
            AiClient aiClient,
            UserProfileMemoryService userProfileMemoryService
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.shortTermMemoryService = shortTermMemoryService;
        this.properties = properties;
        this.privacySanitizer = privacySanitizer;
        this.aiClient = aiClient;
        this.userProfileMemoryService = userProfileMemoryService;
    }

    @Override
    public AgentName name() {
        return AgentName.MEMORY_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return !context.memoryLoaded();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        List<MemoryMessage> redisHistory = shortTermMemoryService.recent(context.session().getPublicId());
        List<AiMessage> previousHistory;
        String source;
        if (!redisHistory.isEmpty()) {
            previousHistory = redisHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
            source = "Redis";
        } else {
            List<ChatMessage> databaseHistory = recentHistory(context.session());
            shortTermMemoryService.refresh(context.session().getPublicId(), databaseHistory.stream()
                    .map(message -> new MemoryMessage(message.getRole(), message.getContent()))
                    .toList());
            previousHistory = databaseHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
            source = "MySQL";
        }

        String profileBrief = userProfileMemoryService.profileBrief(context.user(), context.modelInput());
        String historyBrief = stagedSummary(context.session().getPublicId(), previousHistory);
        context.setPreviousHistory(previousHistory);
        context.setModelHistory(withCurrentUser(previousHistory, context.modelInput()));
        context.setMemoryBrief(combineMemoryBrief(profileBrief, historyBrief));
        context.markMemoryLoaded();
        return AgentDecision.continueWith(
                AgentAction.READ_MEMORY,
                "%s loaded %d messages; memory brief prepared".formatted(source, previousHistory.size()));
    }

    private List<ChatMessage> recentHistory(ChatSession session) {
        if (session.getId() == null) {
            return List.of();
        }
        List<ChatMessage> history = chatMessageRepository.findTop20BySession_IdOrderByCreatedAtDesc(session.getId());
        Collections.reverse(history);
        return history;
    }

    private List<AiMessage> withCurrentUser(List<AiMessage> previousHistory, String currentInput) {
        List<AiMessage> history = new ArrayList<>(previousHistory);
        history.add(AiMessage.user(currentInput));
        int limit = Math.max(2, properties.getChat().getHistoryLimit() * 2);
        return history.stream()
                .skip(Math.max(0, history.size() - limit))
                .toList();
    }

    private String stagedSummary(String sessionId, List<AiMessage> history) {
        if (history.isEmpty()) {
            return "无相关历史记忆。";
        }
        StageSummary cached = shortTermMemoryService.summary(sessionId);
        Long turns = shortTermMemoryService.completedTurns(sessionId);
        boolean hasCached = cached != null && cached.text() != null && !cached.text().isBlank();
        if (hasCached && turns != null && turns >= cached.completedTurns()
                && turns - cached.completedTurns() < SUMMARY_INTERVAL_TURNS) {
            return cached.text();
        }
        String summary = summarizeMemory(history, hasCached ? cached.text() : "无相关历史记忆。");
        if (summary != null) {
            shortTermMemoryService.saveSummary(sessionId, summary, turns == null ? 0 : turns);
            return summary;
        }
        return hasCached ? cached.text() : "无相关历史记忆。";
    }

    private String summarizeMemory(List<AiMessage> history, String previousSummary) {
        try {
            String summary = aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的 MemoryAgent。
                            你的任务是阶段性更新短期对话摘要，结合已有摘要和最近对话。
                            只输出 1-3 条中文要点，不要输出风险等级、诊断结论或后台标签。
                            如果历史没有需要保留的信息，只输出：无相关历史记忆。
                            """),
                    AiMessage.user("""
                            已有摘要：
                            %s

                            最近历史：
                            %s
                            """.formatted(previousSummary, formatHistory(history)))
            )).trim();
            return summary.isBlank() ? null : shorten(summary, 400);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String combineMemoryBrief(String profileBrief, String historyBrief) {
        boolean hasProfile = profileBrief != null && !profileBrief.equals("无已保存用户画像。");
        boolean hasHistory = historyBrief != null && !historyBrief.equals("无相关历史记忆。");
        if (!hasProfile && !hasHistory) {
            return "无相关历史记忆。";
        }
        if (hasProfile && hasHistory) {
            return """
                    用户画像：
                    %s

                    最近对话记忆：
                    %s
                    """.formatted(profileBrief, historyBrief).trim();
        }
        if (hasProfile) {
            return "用户画像：\n" + profileBrief;
        }
        return "最近对话记忆：\n" + historyBrief;
    }

    private String formatHistory(List<AiMessage> history) {
        return String.join("\n", history.stream()
                .skip(Math.max(0, history.size() - 12))
                .map(message -> message.role() + ": " + message.content())
                .toList());
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private AiMessage toAiMessage(ChatMessage chatMessage) {
        String content = privacySanitizer.sanitize(chatMessage.getContent());
        return switch (chatMessage.getRole()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }

    private AiMessage toAiMessage(MemoryMessage memoryMessage) {
        String content = privacySanitizer.sanitize(memoryMessage.content());
        return switch (memoryMessage.role()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }
}
