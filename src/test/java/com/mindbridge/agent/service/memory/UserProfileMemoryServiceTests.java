package com.mindbridge.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.domain.UserMemoryItem;
import com.mindbridge.agent.domain.UserMemoryType;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.repository.UserMemoryItemRepository;
import com.mindbridge.agent.service.PrivacySanitizer;
import com.mindbridge.agent.service.ai.AiClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserProfileMemoryServiceTests {

    @Test
    void mergesDuplicateCandidatesOnceAndPreservesEvidence() {
        Fixture fixture = new Fixture();
        UserMemoryItem existing = new UserMemoryItem();
        existing.setType(UserMemoryType.PREFERENCE);
        existing.setSummary("喜欢简短直接回复");
        existing.setEvidence("earlier evidence");
        existing.setConfidence(0.6);
        fixture.memories.add(existing);
        when(fixture.aiClient.complete(anyList())).thenReturn("""
                [
                  {"type":"PREFERENCE","summary":"喜欢简短直接回复！","evidence":"new evidence","confidence":0.8},
                  {"type":"PREFERENCE","summary":"喜欢 简短直接回复","evidence":"second evidence","confidence":0.9}
                ]
                """);

        fixture.service.rememberUserInput(fixture.user, new ChatSession(), "请记住我喜欢简短直接回复。", "");

        verify(fixture.repository).save(existing);
        verify(fixture.chroma).mirror(existing);
        assertThat(existing.getConfidence()).isEqualTo(0.9);
        assertThat(existing.getEvidence()).contains("earlier evidence", "new evidence", "second evidence");
    }

    private static class Fixture {
        private final UserMemoryItemRepository repository = mock(UserMemoryItemRepository.class);
        private final UserAccountRepository users = mock(UserAccountRepository.class);
        private final UserMemoryChromaGateway chroma = mock(UserMemoryChromaGateway.class);
        private final AiClient aiClient = mock(AiClient.class);
        private final UserAccount user = mock(UserAccount.class);
        private final List<UserMemoryItem> memories = new ArrayList<>();
        private final UserProfileMemoryService service;

        private Fixture() {
            when(user.getId()).thenReturn(1L);
            when(repository.findByUser_IdOrderByUpdatedAtDesc(1L)).thenReturn(memories);
            when(repository.save(any(UserMemoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
            service = new UserProfileMemoryService(repository, users, chroma, new MindBridgeProperties(),
                    aiClient, new ObjectMapper(), new PrivacySanitizer());
        }
    }
}
