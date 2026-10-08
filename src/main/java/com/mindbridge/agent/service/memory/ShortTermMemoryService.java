package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.MessageRole;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 短期记忆服务。
 *
 * <p>每个会话只保留最近 N 轮上下文，完整对话仍然写入 MySQL 作为长期记忆。</p>
 */
@Service
public class ShortTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ShortTermMemoryService.class);
    private static final String KEY_PREFIX = "mindbridge:chat:short-memory:";
    private static final String SUMMARY_SUFFIX = ":summary";
    private static final String TURNS_SUFFIX = ":turns";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MindBridgeProperties properties;

    public ShortTermMemoryService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MindBridgeProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void append(String sessionId, MessageRole role, String content) {
        try {
            String key = key(sessionId);
            String value = objectMapper.writeValueAsString(new MemoryMessage(role, content));
            redisTemplate.opsForList().rightPush(key, value);
            redisTemplate.opsForList().trim(key, -messageLimit(), -1);
            redisTemplate.expire(key, ttl());
            if (role == MessageRole.ASSISTANT) {
                redisTemplate.opsForValue().increment(key + TURNS_SUFFIX);
                redisTemplate.expire(key + TURNS_SUFFIX, ttl());
            }
        } catch (Exception exception) {
            log.debug("Redis short-term memory append skipped: {}", exception.getMessage());
        }
    }

    public List<MemoryMessage> recent(String sessionId) {
        try {
            List<String> values = redisTemplate.opsForList().range(key(sessionId), 0, -1);
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            return values.stream()
                    .map(this::readMessage)
                    .filter(message -> message != null)
                    .toList();
        } catch (Exception exception) {
            log.debug("Redis short-term memory read skipped: {}", exception.getMessage());
            return List.of();
        }
    }

    public void refresh(String sessionId, List<MemoryMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        try {
            String key = key(sessionId);
            redisTemplate.delete(key);
            List<String> values = messages.stream()
                    .skip(Math.max(0, messages.size() - messageLimit()))
                    .map(this::writeMessage)
                    .toList();
            redisTemplate.opsForList().rightPushAll(key, values);
            redisTemplate.expire(key, ttl());
            redisTemplate.delete(key + SUMMARY_SUFFIX);
            long turns = messages.stream().filter(message -> message.role() == MessageRole.ASSISTANT).count();
            redisTemplate.opsForValue().set(key + TURNS_SUFFIX, Long.toString(turns), ttl());
        } catch (Exception exception) {
            log.debug("Redis short-term memory refresh skipped: {}", exception.getMessage());
        }
    }

    public StageSummary summary(String sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(key(sessionId) + SUMMARY_SUFFIX);
            return value == null ? null : objectMapper.readValue(value, StageSummary.class);
        } catch (Exception exception) {
            log.debug("Redis short-term summary read skipped: {}", exception.getMessage());
            return null;
        }
    }

    public Long completedTurns(String sessionId) {
        try {
            String value = redisTemplate.opsForValue().get(key(sessionId) + TURNS_SUFFIX);
            return value == null ? null : Long.valueOf(value);
        } catch (Exception exception) {
            log.debug("Redis short-term turn count read skipped: {}", exception.getMessage());
            return null;
        }
    }

    public void saveSummary(String sessionId, String text, long completedTurns) {
        try {
            String value = objectMapper.writeValueAsString(new StageSummary(text, completedTurns));
            redisTemplate.opsForValue().set(key(sessionId) + SUMMARY_SUFFIX, value, ttl());
        } catch (Exception exception) {
            log.debug("Redis short-term summary write skipped: {}", exception.getMessage());
        }
    }

    private MemoryMessage readMessage(String value) {
        try {
            return objectMapper.readValue(value, MemoryMessage.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private String writeMessage(MemoryMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize memory message", exception);
        }
    }

    private int messageLimit() {
        return Math.max(2, properties.getChat().getHistoryLimit() * 2);
    }

    private Duration ttl() {
        return Duration.ofHours(Math.max(1, properties.getChat().getShortMemoryTtlHours()));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    public record MemoryMessage(MessageRole role, String content) {
    }

    public record StageSummary(String text, long completedTurns) {
    }
}
