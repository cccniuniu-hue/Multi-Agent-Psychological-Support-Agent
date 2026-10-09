package com.mindbridge.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    @Test
    void waitsForTenPendingUserTurnsAndDoesNotExtractAgainWithoutNewMessages() {
        Fixture fixture = new Fixture();
        fixture.pending(9, "普通学习问题");
        fixture.service.rememberConversation(fixture.user, "session", RiskLevel.LOW, "", false);
        verify(fixture.aiClient, never()).complete(anyList());
        assertThat(fixture.session.getProfileMemoryThroughMessageId()).isNull();

        fixture.pending(10, "普通学习问题");
        fixture.service.rememberConversation(fixture.user, "session", RiskLevel.LOW, "", false);
        assertThat(fixture.session.getProfileMemoryThroughMessageId()).isEqualTo(10L);

        fixture.pending(0, "");
        fixture.service.rememberConversation(fixture.user, "session", RiskLevel.LOW, "", false);
        verify(fixture.aiClient).complete(anyList());
    }

    @Test
    void extractsEarlyForExplicitPreferenceOrChangedRisk() {
        Fixture preference = new Fixture();
        preference.pending(1, "以后请先听我说完，再提供建议。");
        preference.service.rememberConversation(preference.user, "session", RiskLevel.LOW, "", false);
        verify(preference.aiClient).complete(anyList());

        Fixture risk = new Fixture();
        risk.session.setProfileMemoryRiskLevel(RiskLevel.LOW);
        risk.pending(1, "最近的状态出现了明显变化。");
        risk.service.rememberConversation(risk.user, "session", RiskLevel.HIGH, "", false);
        verify(risk.aiClient).complete(anyList());
        assertThat(risk.session.getProfileMemoryRiskLevel()).isEqualTo(RiskLevel.HIGH);

        risk.pending(2, "最近状态还是和刚才一样。");
        risk.service.rememberConversation(risk.user, "session", RiskLevel.HIGH, "", false);
        verify(risk.aiClient).complete(anyList());
    }

    @Test
    void retriesFailedExtractionWithoutAdvancingCheckpointOrRisk() {
        Fixture fixture = new Fixture();
        fixture.pending(1, "最近的状态出现了明显变化。");
        when(fixture.aiClient.complete(anyList())).thenThrow(new IllegalStateException("offline")).thenReturn("[]");
        assertThat(fixture.service.rememberConversation(fixture.user, "session", RiskLevel.HIGH, "", false))
                .isFalse();
        assertThat(fixture.session.getProfileMemoryThroughMessageId()).isNull();
        assertThat(fixture.session.getProfileMemoryRiskLevel()).isNull();

        assertThat(fixture.service.rememberConversation(fixture.user, "session", RiskLevel.HIGH, "", false))
                .isTrue();
        verify(fixture.aiClient, times(2)).complete(anyList());
        assertThat(fixture.session.getProfileMemoryThroughMessageId()).isEqualTo(1L);
    }

    @Test
    void endingFlushesPartialBatchAndRepeatedEndDoesNotExtractAgain() {
        Fixture fixture = new Fixture();
        fixture.pending(3, "这是一次普通学习交流。");
        assertThat(fixture.service.rememberConversation(fixture.user, "session", null, "", true)).isTrue();
        assertThat(fixture.session.getProfileMemoryThroughMessageId()).isEqualTo(3L);
        assertThat(fixture.service.rememberConversation(fixture.user, "session", null, "", true)).isTrue();
        verify(fixture.aiClient).complete(anyList());
    }

    @Test
    void endingDrainsOlderBatchesWithoutSkippingPendingMessages() {
        Fixture fixture = new Fixture();
        fixture.pending(10, "这是十轮普通学习交流。");
        List<ChatMessage> remaining = new ArrayList<>();
        for (long id = 11; id <= 13; id++) {
            ChatMessage message = mock(ChatMessage.class);
            when(message.getId()).thenReturn(id);
            when(message.getContent()).thenReturn("这是剩余的普通学习交流。");
            remaining.add(message);
        }
        when(fixture.messages.findTop10BySession_IdAndRoleAndIdGreaterThanOrderByIdAsc(null, MessageRole.USER, 10L))
                .thenReturn(remaining);

        assertThat(fixture.service.rememberConversation(fixture.user, "session", null, "", true)).isTrue();
        assertThat(fixture.session.getProfileMemoryThroughMessageId()).isEqualTo(13L);
        verify(fixture.aiClient, times(2)).complete(anyList());
    }

    private static class Fixture {
        private final UserMemoryItemRepository repository = mock(UserMemoryItemRepository.class);
        private final UserAccountRepository users = mock(UserAccountRepository.class);
        private final UserMemoryChromaGateway chroma = mock(UserMemoryChromaGateway.class);
        private final AiClient aiClient = mock(AiClient.class);
        private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
        private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
        private final ChatSession session = new ChatSession();
        private final UserAccount user = mock(UserAccount.class);
        private final List<UserMemoryItem> memories = new ArrayList<>();
        private final UserProfileMemoryService service;

        private Fixture() {
            when(user.getId()).thenReturn(1L);
            when(repository.findByUser_IdOrderByUpdatedAtDesc(1L)).thenReturn(memories);
            when(repository.save(any(UserMemoryItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(sessions.findForMemoryUpdate("session", 1L)).thenReturn(Optional.of(session));
            when(aiClient.complete(anyList())).thenReturn("[]");
            service = new UserProfileMemoryService(repository, users, sessions, messages, chroma, new MindBridgeProperties(),
                    aiClient, new ObjectMapper(), new PrivacySanitizer());
        }

        private void pending(int count, String content) {
            List<ChatMessage> pending = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                ChatMessage message = mock(ChatMessage.class);
                when(message.getId()).thenReturn((long) i);
                when(message.getContent()).thenReturn(content);
                pending.add(message);
            }
            when(messages.findTop10BySession_IdAndRoleAndIdGreaterThanOrderByIdAsc(
                    session.getId(), MessageRole.USER,
                    session.getProfileMemoryThroughMessageId() == null ? 0L : session.getProfileMemoryThroughMessageId()))
                    .thenReturn(pending);
        }
    }
}
