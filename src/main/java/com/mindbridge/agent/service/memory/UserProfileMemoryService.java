package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.domain.UserMemoryItem;
import com.mindbridge.agent.domain.UserMemoryType;
import com.mindbridge.agent.repository.UserMemoryItemRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final UserMemoryChromaGateway userMemoryChromaGateway;
    private final MindBridgeProperties properties;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final PrivacySanitizer privacySanitizer;

    public UserProfileMemoryService(
            UserMemoryItemRepository userMemoryItemRepository,
            UserAccountRepository userAccountRepository,
            UserMemoryChromaGateway userMemoryChromaGateway,
            MindBridgeProperties properties,
            AiClient aiClient,
            ObjectMapper objectMapper,
            PrivacySanitizer privacySanitizer
    ) {
        this.userMemoryItemRepository = userMemoryItemRepository;
        this.userAccountRepository = userAccountRepository;
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
                .map(memory -> "- %s：%s".formatted(typeLabel(memory.getType()), memory.getSummary()))
                .toList());
    }

    @Transactional
    public void rememberUserInput(
            UserAccount user,
            ChatSession session,
            String input,
            String memoryBrief
    ) {
        String sanitizedInput = privacySanitizer.sanitize(input);
        if (sanitizedInput.length() < 6) {
            return;
        }
        List<MemoryCandidate> candidates = extractCandidates(sanitizedInput, memoryBrief);
        if (candidates.isEmpty()) {
            return;
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
    }

    @Transactional
    public void deleteMemory(Long userId, Long memoryId) {
        UserMemoryItem memory = userMemoryItemRepository.findById(memoryId)
                .orElseThrow(() -> new IllegalArgumentException("Memory item not found"));
        if (!memory.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Memory item not found");
        }
        userMemoryChromaGateway.delete(memory.getId());
        userMemoryItemRepository.delete(memory);
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
        userMemoryItemRepository.findAllById(ids).forEach(item -> {
            if (item.getUser().getId().equals(user.getId())) {
                byId.put(item.getId(), item);
            }
        });
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

                            本轮用户输入：
                            %s
                            """.formatted(memoryBrief, input))
            )).trim());
        } catch (Exception ignored) {
            return fallbackCandidates(input);
        }
    }

    private List<MemoryCandidate> parseCandidates(String response) throws Exception {
        String json = stripCodeFence(response);
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
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
        String normalized = input.toLowerCase(Locale.ROOT);
        if (containsAny(
                normalized,
                "以后请",
                "以后帮我",
                "请记住",
                "记住我",
                "我喜欢",
                "我不喜欢",
                "我更喜欢",
                "我希望你",
                "以后不要",
                "请不要")) {
            return List.of(new MemoryCandidate(
                    UserMemoryType.PREFERENCE,
                    shorten(input, 60),
                    shorten(input, 80),
                    0.6));
        }
        return List.of();
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
                .forEach(this::deletePrunedMemory);
    }

    private void deletePrunedMemory(UserMemoryItem item) {
        userMemoryChromaGateway.delete(item.getId());
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
        return !containsAny(summary, "[手机号]", "[学号]", "[证件号]", "[姓名]", "诊断为", "风险等级");
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
        return shorten(value == null ? "" : value.replaceAll("\\s+", " ").trim(), 120);
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
