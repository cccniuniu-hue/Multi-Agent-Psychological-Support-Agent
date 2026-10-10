package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.domain.UserMemoryItem;
import com.mindbridge.agent.domain.UserMemoryType;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.repository.ChatSessionRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.repository.UserMemoryItemRepository;
import com.mindbridge.agent.service.PrivacySanitizer;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.memory.UserMemoryChromaGateway.UserMemoryMatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
/**
 * 用户画像长期记忆服务。
 *
 * <p>从学生对话中抽取稳定偏好和可复用背景，写入关系库作为可审计记录，
 * 同时镜像到 Chroma 做语义召回。</p>
 */
public class UserProfileMemoryService {

    private static final int PROFILE_BRIEF_LIMIT = 8;
    private static final int MAX_MEMORY_ITEMS = 40;
    private static final double MIN_CONFIDENCE = 0.55;

    private final UserMemoryItemRepository userMemoryItemRepository;
    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UserMemoryChromaGateway userMemoryChromaGateway;
    private final MindBridgeProperties properties;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final PrivacySanitizer privacySanitizer;

    public UserProfileMemoryService(
            UserMemoryItemRepository userMemoryItemRepository,
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            UserMemoryChromaGateway userMemoryChromaGateway,
            MindBridgeProperties properties,
            AiClient aiClient,
            ObjectMapper objectMapper,
            PrivacySanitizer privacySanitizer
    ) {
        this.userMemoryItemRepository = userMemoryItemRepository;
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.userMemoryChromaGateway = userMemoryChromaGateway;
        this.properties = properties;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.privacySanitizer = privacySanitizer;
    }

    @Transactional(readOnly = true)
    public List<UserMemoryItem> memoriesForUser(Long userId) {
        return userMemoryItemRepository.findByUser_IdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public String profileBrief(UserAccount user) {
        return profileBrief(user, "");
    }

    @Transactional(readOnly = true)
    public String profileBrief(UserAccount user, String currentInput) {
        List<UserMemoryItem> memories = recallMemories(user, currentInput);
        if (memories.isEmpty()) {
            return "无已保存用户画像。";
        }
        return String.join("\n", memories.stream()
                .limit(PROFILE_BRIEF_LIMIT)
                .map(memory -> "- %s：%s".formatted(typeLabel(memory.getType()), privacySanitizer.sanitize(memory.getSummary())))
                .toList());
    }

    @Transactional
    public boolean rememberConversation(
            UserAccount user, String sessionId, RiskLevel riskLevel, String memoryBrief, boolean ending
    ) {
        ChatSession session = chatSessionRepository.findForMemoryUpdate(sessionId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        RiskLevel previousRisk = session.getProfileMemoryRiskLevel() == null
                ? RiskLevel.LOW : session.getProfileMemoryRiskLevel();
        boolean riskChanged = riskLevel != null && riskLevel != previousRisk;
        while (true) {
            long throughId = session.getProfileMemoryThroughMessageId() == null
                    ? 0L : session.getProfileMemoryThroughMessageId();
            List<ChatMessage> pending = chatMessageRepository
                    .findTop10BySession_IdAndRoleAndIdGreaterThanOrderByIdAsc(session.getId(), MessageRole.USER, throughId);
            // shortcut: 重要变化用风险变化及明确偏好表达识别，需更细粒度情绪变化时再扩展。
            boolean due = ending || pending.size() >= 10 || riskChanged
                    || pending.stream().anyMatch(message -> hasExplicitPreference(message.getContent()));
            if (pending.isEmpty() || !due) {
                if (riskLevel != null) {
                    session.setProfileMemoryRiskLevel(riskLevel);
                }
                chatSessionRepository.save(session);
                return true;
            }
            String input = String.join("\n", pending.stream()
                    .map(message -> shorten(privacySanitizer.sanitize(message.getContent()), 800))
                    .toList());
            if (!rememberUserInput(user, session, input, memoryBrief)) {
                return false;
            }
            session.setProfileMemoryThroughMessageId(pending.get(pending.size() - 1).getId());
            if (riskLevel != null) {
                session.setProfileMemoryRiskLevel(riskLevel);
            }
            chatSessionRepository.save(session);
            if (!ending) {
                return true;
            }
        }
    }

    boolean rememberUserInput(
            UserAccount user,
            ChatSession session,
            String input,
            String memoryBrief
    ) {
        String sanitizedInput = privacySanitizer.sanitize(input);
        if (sanitizedInput.length() < 6) {
            return true;
        }
        List<MemoryCandidate> candidates = extractCandidates(sanitizedInput, memoryBrief);
        if (candidates == null) {
            return false;
        }
        if (candidates.isEmpty()) {
            return true;
        }
        Map<String, MemoryCandidate> unique = new LinkedHashMap<>();
        for (MemoryCandidate candidate : candidates) {
            if (!isUsable(candidate)) {
                continue;
            }
            unique.merge(candidate.type() + ":" + normalize(candidate.summary()), candidate, (first, next) ->
                    new MemoryCandidate(first.type(),
                            next.confidence() > first.confidence() ? next.summary() : first.summary(),
                            mergeEvidence(first.evidence(), next.evidence()),
                            Math.max(first.confidence(), next.confidence())));
        }
        List<UserMemoryItem> existing = new ArrayList<>(
                userMemoryItemRepository.findByUser_IdOrderByUpdatedAtDesc(user.getId()));
        for (MemoryCandidate candidate : unique.values()) {
            upsert(user, session, candidate, existing);
        }
        prune(user.getId());
        return true;
    }

    @Transactional
    public void deleteMemory(Long userId, Long memoryId) {
        UserMemoryItem memory = userMemoryItemRepository.findByIdAndUser_Id(memoryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory item not found"));
        deleteStoredMemory(memory);
    }

    private List<UserMemoryItem> recallMemories(UserAccount user, String currentInput) {
        List<UserMemoryMatch> matches = userMemoryChromaGateway.query(
                user.getId(),
                currentInput,
                properties.getMemory().getTopK());
        if (matches.isEmpty()) {
            return userMemoryItemRepository.findTop12ByUser_IdOrderByUpdatedAtDesc(user.getId());
        }
        List<Long> ids = matches.stream()
                .map(UserMemoryMatch::memoryId)
                .distinct()
                .toList();
        Map<Long, UserMemoryItem> byId = new LinkedHashMap<>();
        userMemoryItemRepository.findByUser_IdAndIdIn(user.getId(), ids)
                .forEach(item -> byId.put(item.getId(), item));
        List<UserMemoryItem> recalled = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        if (recalled.isEmpty()) {
            return userMemoryItemRepository.findTop12ByUser_IdOrderByUpdatedAtDesc(user.getId());
        }
        return recalled;
    }

    private List<MemoryCandidate> extractCandidates(String input, String memoryBrief) {
        try {
            return parseCandidates(aiClient.complete(List.of(
                    AiMessage.system("""
                            你是 MindBridge 的用户画像记忆抽取器。
                            只提取对后续对话有稳定帮助的长期记忆：用户偏好、沟通风格、支持需求、个人背景、反复出现的状态模式。
                            不要保存诊断结论、风险等级、手机号、学号、证件号、真实姓名、详细地址或一次性的临时任务。
                            若没有值得长期保存的信息，只输出 []。
                            必须只输出 JSON 数组，每项字段：
                            type: PREFERENCE | COMMUNICATION_STYLE | SUPPORT_NEED | PERSONAL_CONTEXT | WELLBEING_PATTERN
                            summary: 30 字以内中文摘要
                            evidence: 60 字以内脱敏证据
                            confidence: 0 到 1 的数字
                            """),
                    AiMessage.user("""
                            已有记忆摘要：
                            %s

                            待处理的用户对话（按时间顺序）：
                            %s
                            """.formatted(privacySanitizer.sanitize(memoryBrief), input))
            )).trim());
        } catch (Exception ignored) {
            List<MemoryCandidate> fallback = fallbackCandidates(input);
            return fallback.isEmpty() ? null : fallback;
        }
    }

    private List<MemoryCandidate> parseCandidates(String response) throws Exception {
        String json = stripCodeFence(response);
        JsonNode root = objectMapper.readTree(json);
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Memory extraction must return a JSON array");
        }
        List<MemoryCandidate> candidates = new ArrayList<>();
        for (JsonNode node : root) {
            UserMemoryType type = parseType(node.path("type").asText());
            String summary = clean(node.path("summary").asText());
            String evidence = clean(node.path("evidence").asText());
            double confidence = clamp(node.path("confidence").asDouble(0.0));
            if (type != null && !summary.isBlank()) {
                candidates.add(new MemoryCandidate(type, summary, evidence, confidence));
            }
        }
        return candidates;
    }

    private List<MemoryCandidate> fallbackCandidates(String input) {
        return input.lines().filter(this::hasExplicitPreference)
                .map(line -> new MemoryCandidate(UserMemoryType.PREFERENCE,
                        shorten(line, 60), shorten(line, 80), 0.6))
                .toList();
    }

    private boolean hasExplicitPreference(String input) {
        return containsAny(input,
                "以后请",
                "以后帮我",
                "请记住",
                "记住我",
                "我喜欢",
                "我不喜欢",
                "我更喜欢",
                "我希望你",
                "以后不要",
                "请不要");
    }

    private void upsert(
            UserAccount user,
            ChatSession session,
            MemoryCandidate candidate,
            List<UserMemoryItem> existing
    ) {
        String normalizedSummary = normalize(candidate.summary());
        for (UserMemoryItem item : existing) {
            if (item.getType() == candidate.type() && normalize(item.getSummary()).equals(normalizedSummary)) {
                item.refreshSeen(session, mergeEvidence(item.getEvidence(), candidate.evidence()), candidate.confidence());
                UserMemoryItem saved = userMemoryItemRepository.save(item);
                userMemoryChromaGateway.mirror(saved);
                return;
            }
        }
        UserMemoryItem item = new UserMemoryItem();
        item.setUser(userAccountRepository.getReferenceById(user.getId()));
        item.setSourceSession(session);
        item.setType(candidate.type());
        item.setSummary(candidate.summary());
        item.setEvidence(candidate.evidence());
        item.setConfidence(candidate.confidence());
        UserMemoryItem saved = userMemoryItemRepository.save(item);
        userMemoryChromaGateway.mirror(saved);
        existing.add(saved);
    }

    private void prune(Long userId) {
        List<UserMemoryItem> all = userMemoryItemRepository.findByUser_IdOrderByUpdatedAtDesc(userId);
        if (all.size() <= MAX_MEMORY_ITEMS) {
            return;
        }
        all.stream()
                .skip(MAX_MEMORY_ITEMS)
                .forEach(this::deleteStoredMemory);
    }

    private void deleteStoredMemory(UserMemoryItem item) {
        if (!userMemoryChromaGateway.delete(item.getUser().getId(), item.getId())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "画像索引删除失败，记录已保留，请稍后重试。");
        }
        userMemoryItemRepository.delete(item);
    }

    private boolean isUsable(MemoryCandidate candidate) {
        if (candidate.confidence() < MIN_CONFIDENCE) {
            return false;
        }
        String summary = candidate.summary();
        if (summary.length() < 4 || summary.length() > 80) {
            return false;
        }
        return !containsAny(summary, "[手机号]", "[学号]", "[证件号]", "[姓名]", "[邮箱]", "[地址]", "诊断为", "风险等级");
    }

    private UserMemoryType parseType(String value) {
        try {
            return UserMemoryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripCodeFence(String response) {
        if (response.startsWith("```")) {
            return response.replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("(?s)\\s*```$", "")
                    .trim();
        }
        return response;
    }

    private String clean(String value) {
        return shorten(privacySanitizer.sanitize(value).replaceAll("\\s+", " ").trim(), 120);
    }

    private String mergeEvidence(String previous, String current) {
        String oldEvidence = clean(previous);
        String newEvidence = clean(current);
        if (newEvidence.isBlank() || oldEvidence.contains(newEvidence)) {
            return oldEvidence;
        }
        if (oldEvidence.isBlank() || newEvidence.contains(oldEvidence)) {
            return newEvidence;
        }
        return shorten(newEvidence + "；" + oldEvidence, 120);
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s，。！？、；：“”‘’（）【】《》]", "");
    }

    private String typeLabel(UserMemoryType type) {
        return switch (type) {
            case PREFERENCE -> "偏好";
            case COMMUNICATION_STYLE -> "沟通方式";
            case SUPPORT_NEED -> "支持需求";
            case PERSONAL_CONTEXT -> "个人背景";
            case WELLBEING_PATTERN -> "状态模式";
        };
    }

    private record MemoryCandidate(
            UserMemoryType type,
            String summary,
            String evidence,
            double confidence
    ) {
    }
}
