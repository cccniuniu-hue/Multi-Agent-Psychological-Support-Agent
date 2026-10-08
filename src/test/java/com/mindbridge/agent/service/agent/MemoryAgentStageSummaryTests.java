package com.mindbridge.agent.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.service.PrivacySanitizer;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.mindbridge.agent.service.memory.ShortTermMemoryService.StageSummary;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryAgentStageSummaryTests {

    @Test
    void reusesRedisSummaryBetweenUpdateStages() {
        Fixture fixture = new Fixture();
        when(fixture.shortTerm.recent("s1")).thenReturn(List.of(new MemoryMessage(MessageRole.USER, "earlier")));
        when(fixture.shortTerm.summary("s1")).thenReturn(new StageSummary("cached brief", 2));
        when(fixture.shortTerm.completedTurns("s1")).thenReturn(4L);

        AgentContext context = fixture.run(new ChatSession());

        assertThat(context.memoryBrief()).contains("cached brief");
        assertThat(context.modelHistory()).extracting(AiMessage::content).contains("earlier", "current");
        verify(fixture.aiClient, never()).complete(anyList());
    }

    @Test
    void updatesSummaryAfterFiveCompletedTurns() {
        Fixture fixture = new Fixture();
        when(fixture.shortTerm.recent("s1")).thenReturn(List.of(new MemoryMessage(MessageRole.USER, "earlier")));
        when(fixture.shortTerm.summary("s1")).thenReturn(new StageSummary("cached brief", 2));
        when(fixture.shortTerm.completedTurns("s1")).thenReturn(7L);
        when(fixture.aiClient.complete(anyList())).thenReturn("updated brief");

        AgentContext context = fixture.run(new ChatSession());

        assertThat(context.memoryBrief()).contains("updated brief");
        verify(fixture.shortTerm).saveSummary("s1", "updated brief", 7L);
    }

    @Test
    void rebuildsFromMysqlWhenRedisHistoryIsMissing() {
        Fixture fixture = new Fixture();
        ChatSession session = mock(ChatSession.class);
        when(session.getPublicId()).thenReturn("s1");
        when(session.getId()).thenReturn(1L);
        ChatMessage message = mock(ChatMessage.class);
        when(message.getRole()).thenReturn(MessageRole.USER);
        when(message.getContent()).thenReturn("db history");
        when(fixture.chatMessages.findTop20BySession_IdOrderByCreatedAtDesc(1L))
                .thenReturn(new ArrayList<>(List.of(message)));
        when(fixture.aiClient.complete(anyList())).thenReturn("mysql brief");

        AgentContext context = fixture.run(session);

        assertThat(context.memoryBrief()).contains("mysql brief");
        assertThat(context.modelHistory()).extracting(AiMessage::content).contains("db history");
        verify(fixture.shortTerm).refresh(eq("s1"), anyList());
    }

    private static class Fixture {
        private final ChatMessageRepository chatMessages = mock(ChatMessageRepository.class);
        private final ShortTermMemoryService shortTerm = mock(ShortTermMemoryService.class);
        private final UserProfileMemoryService profile = mock(UserProfileMemoryService.class);
        private final AiClient aiClient = mock(AiClient.class);
        private final MemoryAgent agent;

        private Fixture() {
            when(profile.profileBrief(any(UserAccount.class), anyString()))
                    .thenReturn("无已保存用户画像。");
            agent = new MemoryAgent(chatMessages, shortTerm, new MindBridgeProperties(),
                    new PrivacySanitizer(), aiClient, profile);
        }

        private AgentContext run(ChatSession session) {
            if (session.getPublicId() == null) {
                session.setPublicId("s1");
            }
            AgentContext context = new AgentContext(new UserAccount(), session, "current", "current");
            agent.act(context);
            return context;
        }
    }
}
