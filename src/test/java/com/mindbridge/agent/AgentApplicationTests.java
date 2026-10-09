package com.mindbridge.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.ChatMessage;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.AgentRunTraceResponse;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.repository.ChatSessionRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.AgentRunTraceService;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentStep;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "mindbridge.ai.deepseek.api-key=test-key"
})
class AgentApplicationTests {

    @Autowired
    private AgentRunTraceService agentRunTraceService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void profileCheckpointSurvivesActivityUpdatesAndPendingQueryOnlyReturnsOwnedUserTurns() {
        UserAccount user = userAccountRepository.findByUsername("student").orElseThrow();
        ChatSession session = new ChatSession();
        session.setPublicId("profile-checkpoint-session");
        session.setUser(user);
        session = chatSessionRepository.save(session);
        Long firstUserMessageId = null;
        for (int i = 0; i < 22; i++) {
            ChatMessage message = new ChatMessage();
            message.setUser(user);
            message.setSession(session);
            message.setRole(i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
            message.setContent("synthetic message " + i);
            message = chatMessageRepository.save(message);
            if (i == 0) {
                firstUserMessageId = message.getId();
            }
        }
        chatMessageRepository.flush();
        entityManager.clear();

        ChatSession locked = chatSessionRepository.findForMemoryUpdate(session.getPublicId(), user.getId()).orElseThrow();
        assertThat(chatSessionRepository.findForMemoryUpdate(session.getPublicId(), -1L)).isEmpty();
        List<ChatMessage> pending = chatMessageRepository.findTop10BySession_IdAndRoleAndIdGreaterThanOrderByIdAsc(
                session.getId(), MessageRole.USER, firstUserMessageId);
        assertThat(pending).hasSize(10).allMatch(message -> message.getRole() == MessageRole.USER);
        assertThat(pending).extracting(ChatMessage::getId).isSorted().doesNotContain(firstUserMessageId);
        locked.setProfileMemoryThroughMessageId(pending.get(9).getId());
        locked.setProfileMemoryRiskLevel(RiskLevel.HIGH);
        chatSessionRepository.saveAndFlush(locked);
        chatSessionRepository.updateActivity(session.getId(), Instant.now());
        entityManager.clear();

        ChatSession reloaded = chatSessionRepository.findById(session.getId()).orElseThrow();
        assertThat(reloaded.getProfileMemoryThroughMessageId()).isEqualTo(pending.get(9).getId());
        assertThat(reloaded.getProfileMemoryRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void persistsAndReadsAgentRunTrace() {
        UserAccount user = new UserAccount();
        user.setUsername("trace-student");
        user.setDisplayName("Trace Student");
        user.setPassword("encoded");
        user.setRoles(Set.of("ROLE_USER"));
        user = userAccountRepository.save(user);

        ChatSession session = new ChatSession();
        session.setPublicId("trace-session");
        session.setTitle("Trace session");
        session.setUser(user);
        session = chatSessionRepository.save(session);

        ChatMessage message = new ChatMessage();
        message.setUser(user);
        message.setSession(session);
        message.setRole(MessageRole.USER);
        message.setContent("hello trace");
        message = chatMessageRepository.save(message);

        Instant startedAt = Instant.now();
        AgentRunResult result = new AgentRunResult(
                IntentType.CHAT,
                RiskLevel.LOW,
                null,
                List.of(),
                List.of(),
                List.of(),
                "memory loaded",
                null,
                "answer naturally",
                AgentName.COMPANION_AGENT,
                List.of(new AgentStep(
                        1,
                        AgentName.MEMORY_AGENT,
                        AgentAction.READ_MEMORY,
                        "loaded short-term memory",
                        startedAt))
        );

        String traceId = agentRunTraceService
                .saveRun(user, session, message, "hello trace", result, startedAt, Instant.now())
                .getTraceId();

        AgentRunTraceResponse trace = agentRunTraceService.trace(traceId);
        assertThat(trace.sessionId()).isEqualTo("trace-session");
        assertThat(trace.stepCount()).isEqualTo(1);
        assertThat(trace.steps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.agent()).isEqualTo(AgentName.MEMORY_AGENT);
                    assertThat(step.action()).isEqualTo(AgentAction.READ_MEMORY);
                    assertThat(step.observation()).isEqualTo("loaded short-term memory");
                });
        assertThat(agentRunTraceService.tracesForSession("trace-session"))
                .extracting(AgentRunTraceResponse::traceId)
                .contains(traceId);
    }
}
