package com.mindbridge.agent.service;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.domain.PsychologicalReport;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.ChatRequest;
import com.mindbridge.agent.dto.ChatStreamEvent;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.repository.ChatSessionRepository;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentRuntimeService;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
/**
 * 学生聊天主流程服务。
 *
 * <p>负责会话落库、模型流式调用和后台报告触发；意图路由、记忆读取、RAG 与风险评估
 * 由 AgentRuntimeService 中的多 Agent loop 完成。</p>
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PsychologicalReportRepository reportRepository;
    private final MindBridgeProperties properties;
    private final ToolOrchestrationService toolOrchestrationService;
    private final PrivacySanitizer privacySanitizer;
    private final ShortTermMemoryService shortTermMemoryService;
    private final UserProfileMemoryService userProfileMemoryService;
    private final AgentRuntimeService agentRuntimeService;
    private final AgentRunTraceService agentRunTraceService;
    private final AiClient aiClient;

    public ChatService(
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            PsychologicalReportRepository reportRepository,
            MindBridgeProperties properties,
            ToolOrchestrationService toolOrchestrationService,
            PrivacySanitizer privacySanitizer,
            ShortTermMemoryService shortTermMemoryService,
            UserProfileMemoryService userProfileMemoryService,
            AgentRuntimeService agentRuntimeService,
            AgentRunTraceService agentRunTraceService,
            AiClient aiClient
    ) {
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.reportRepository = reportRepository;
        this.properties = properties;
        this.toolOrchestrationService = toolOrchestrationService;
        this.privacySanitizer = privacySanitizer;
        this.shortTermMemoryService = shortTermMemoryService;
        this.userProfileMemoryService = userProfileMemoryService;
        this.agentRuntimeService = agentRuntimeService;
        this.agentRunTraceService = agentRunTraceService;
        this.aiClient = aiClient;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(Long userId, ChatRequest request) {
        // 聊天接口使用 SSE 流式返回；数据库读写放到 boundedElastic，避免阻塞响应线程。
        return Mono.fromCallable(() -> prepare(userId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::streamPrepared)
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(null, "服务暂时不可用：" + exception.getMessage()))));
    }

    public void endSession(Long userId, String sessionId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        chatSessionRepository.findByPublicIdAndUser_Id(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!userProfileMemoryService.rememberConversation(user, sessionId, null, "", true)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会话整理暂时失败，请稍后重试。");
        }
    }

    private PreparedConversation prepare(Long userId, ChatRequest request) {
        String input = request.message().trim();
        String modelInput = privacySanitizer.sanitize(input);
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatSession session = resolveSession(user, request.sessionId(), input);
        Instant startedAt = Instant.now();
        AgentRunResult agentRun = agentRuntimeService.run(user, session, input, modelInput);
        Instant completedAt = Instant.now();
        ChatMessage userMessage = saveMessage(user, session, MessageRole.USER, input);
        agentRunTraceService.saveRun(user, session, userMessage, input, agentRun, startedAt, completedAt);
        rememberUserProfile(user, session, agentRun.riskLevel(), agentRun.memoryBrief());

        PsychologicalReport report = null;
        if (agentRun.requiresReport()) {
            report = saveReport(user, session, input, agentRun.intent(), agentRun.assessment());
        }

        RiskLevel riskLevel = agentRun.riskLevel() == null ? RiskLevel.LOW : agentRun.riskLevel();
        List<AiMessage> messages = agentRun.responseMessages().isEmpty()
                ? buildMessages(user, agentRun.intent(), riskLevel, agentRun.retrievedKnowledge(), agentRun.modelHistory())
                : agentRun.responseMessages();
        Long reportId = report == null ? null : report.getId();
        return new PreparedConversation(user, session, agentRun.intent(), riskLevel, messages, reportId);
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> streamPrepared(PreparedConversation prepared) {
        StringBuilder assistantReply = new StringBuilder();
        Flux<ServerSentEvent<ChatStreamEvent>> meta = Flux.just(event(
                "meta",
                ChatStreamEvent.meta(prepared.session().getPublicId())));

        Flux<ServerSentEvent<ChatStreamEvent>> tokens = aiClient.stream(prepared.messages())
                .doOnNext(assistantReply::append)
                .map(token -> event("token", ChatStreamEvent.token(prepared.session().getPublicId(), token)))
                .timeout(Duration.ofSeconds(45))
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(prepared.session().getPublicId(), "模型响应超时或失败，请稍后重试。"))))
                .switchIfEmpty(Flux.just(event(
                        "error",
                        ChatStreamEvent.error(prepared.session().getPublicId(), "模型没有返回内容，请稍后重试。"))));

        Mono<ServerSentEvent<ChatStreamEvent>> done = Mono.fromCallable(() -> {
            if (!assistantReply.isEmpty()) {
                saveMessage(prepared.user(), prepared.session(), MessageRole.ASSISTANT, assistantReply.toString());
            }
            // 工具链在模型回复完成后异步执行，不打断学生端正在进行的对话体验。
            if (prepared.reportId() != null) {
                toolOrchestrationService.handleAsync(prepared.reportId());
            }
            return event("done", ChatStreamEvent.done(prepared.session().getPublicId()));
        }).subscribeOn(Schedulers.boundedElastic());

        return meta.concatWith(tokens).concatWith(done);
    }

    private ChatSession resolveSession(UserAccount user, String publicId, String input) {
        if (publicId != null && !publicId.isBlank()) {
            return chatSessionRepository.findByPublicIdAndUser_Id(publicId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        }
        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        session.setTitle(input.length() > 36 ? input.substring(0, 36) : input);
        return chatSessionRepository.save(session);
    }

    private ChatMessage saveMessage(UserAccount user, ChatSession session, MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUser(user);
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);
        session.touch();
        // 只更新活跃时间，避免流式回复使用的旧实体覆盖画像抽取进度。
        chatSessionRepository.updateActivity(session.getId(), session.getUpdatedAt());
        shortTermMemoryService.append(session.getPublicId(), role, content);
        return message;
    }

    private void rememberUserProfile(UserAccount user, ChatSession session, RiskLevel riskLevel, String memoryBrief) {
        try {
            userProfileMemoryService.rememberConversation(user, session.getPublicId(), riskLevel, memoryBrief, false);
        } catch (Exception exception) {
            log.debug("User profile memory update skipped: {}", exception.getMessage());
        }
    }

    private PsychologicalReport saveReport(
            UserAccount user,
            ChatSession session,
            String content,
            IntentType intent,
            PsychologyAssessment assessment
    ) {
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setSession(session);
        report.setContent(content);
        report.setIntent(intent);
        report.setEmotion(assessment.emotion());
        report.setEmotionScore(assessment.emotionScore());
        report.setRiskLevel(assessment.risk());
        report.setConfidence(assessment.confidence());
        report.setSummary(assessment.summary());
        return reportRepository.save(report);
    }

    private List<AiMessage> buildMessages(
            UserAccount user,
            IntentType intent,
            RiskLevel riskLevel,
            List<SearchResult> retrieved,
            List<AiMessage> history
    ) {
        // 检索片段只作为系统上下文给模型使用，不直接展示后台评估信息给学生。
        String context = String.join("\n\n", retrieved.stream()
                .map(result -> "- [" + result.source() + "] " + result.content())
                .toList());
        List<AiMessage> messages = new ArrayList<>();
        messages.add(PromptTemplates.answerSystemPrompt(intent, riskLevel, context, user.getDisplayName()));

        int limit = messageWindowLimit();
        history.stream()
                .skip(Math.max(0, history.size() - limit))
                .forEach(messages::add);
        return messages;
    }

    private int messageWindowLimit() {
        // history-limit 以轮次理解，这里乘 2 保留用户和助手两侧消息。
        return Math.max(2, properties.getChat().getHistoryLimit() * 2);
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private record PreparedConversation(
            UserAccount user,
            ChatSession session,
            IntentType intent,
            RiskLevel riskLevel,
            List<AiMessage> messages,
            Long reportId
    ) {
    }
}
