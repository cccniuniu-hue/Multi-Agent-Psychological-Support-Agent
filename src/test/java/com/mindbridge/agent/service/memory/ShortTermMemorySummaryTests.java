package com.mindbridge.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.StageSummary;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ShortTermMemorySummaryTests {

    @Test
    void storesSummaryAndCountsOnlyCompletedAssistantTurns() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> list = mock(ListOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(list);
        when(redis.opsForValue()).thenReturn(values);
        String summaryKey = "mindbridge:chat:short-memory:s1:summary";
        String turnsKey = "mindbridge:chat:short-memory:s1:turns";
        AtomicReference<String> cached = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            cached.set(invocation.getArgument(1));
            return null;
        }).when(values).set(eq(summaryKey), anyString(), any(Duration.class));
        when(values.get(summaryKey)).thenAnswer(invocation -> cached.get());
        when(values.get(turnsKey)).thenReturn("3");
        ShortTermMemoryService service = new ShortTermMemoryService(redis, new ObjectMapper(), new MindBridgeProperties());

        service.append("s1", MessageRole.USER, "user text");
        service.append("s1", MessageRole.ASSISTANT, "reply text");
        service.saveSummary("s1", "short brief", 3);

        verify(values).increment(turnsKey);
        assertThat(service.completedTurns("s1")).isEqualTo(3);
        assertThat(service.summary("s1")).isEqualTo(new StageSummary("short brief", 3));
    }

    @Test
    void mysqlRefreshInvalidatesOldSummaryAndRebuildsTurnCount() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ListOperations<String, String> list = mock(ListOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForList()).thenReturn(list);
        when(redis.opsForValue()).thenReturn(values);
        ShortTermMemoryService service = new ShortTermMemoryService(redis, new ObjectMapper(), new MindBridgeProperties());

        service.refresh("s1", List.of(
                new MemoryMessage(MessageRole.USER, "question"),
                new MemoryMessage(MessageRole.ASSISTANT, "answer")));

        verify(redis).delete("mindbridge:chat:short-memory:s1:summary");
        verify(values).set(eq("mindbridge:chat:short-memory:s1:turns"), eq("1"), any(Duration.class));
    }
}
