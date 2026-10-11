package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.MessageRole;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.AgentRunTraceRepository;
import com.mindbridge.agent.repository.ChatSessionRepository;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.ToolOrchestrationService;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-api-sse-harness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindbridge.knowledge.use-chroma=false",
        "mindbridge.memory.use-chroma=false",
        "mindbridge.knowledge.reranker-enabled=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@AutoConfigureWebTestClient
class ApiSseHarnessTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PsychologicalReportRepository reportRepository;

    @Autowired
    private AgentRunTraceRepository traceRepository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private UserAccountRepository userRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @MockBean
    private KnowledgeService knowledgeService;

    @MockBean
    private AiClient aiClient;

    @MockBean
    private ShortTermMemoryService shortTermMemoryService;

    @MockBean
    private UserProfileMemoryService userProfileMemoryService;

    @MockBean
    private ToolOrchestrationService toolOrchestrationService;

    private ScriptedAiClient scriptedAiClient;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        traceRepository.deleteAll();
        scriptedAiClient = new ScriptedAiClient();
        when(aiClient.complete(anyList())).thenAnswer(invocation ->
                scriptedAiClient.complete(invocation.getArgument(0)));
        when(aiClient.stream(anyList())).thenAnswer(invocation ->
                scriptedAiClient.stream(invocation.getArgument(0)));
        when(shortTermMemoryService.recent(anyString())).thenReturn(List.of());
        when(userProfileMemoryService.profileBrief(any(UserAccount.class), anyString()))
                .thenReturn("无已保存用户画像。");
    }

    @Test
    void studentChatReturnsSseMetaTokenAndDoneWithoutReportForChat() {
        String body = postChat("student", "student123", "帮我解释一下 Java 多线程。");

        assertThat(body)
                .contains("event:meta")
                .contains("event:token")
                .contains("event:done")
                .contains("这是一个稳定的测试回复。");
        assertThat(reportRepository.findAll()).isEmpty();
        verify(userProfileMemoryService).rememberConversation(
                any(UserAccount.class), anyString(), eq(RiskLevel.LOW), anyString(), eq(false));
        assertThat(traceRepository.findAll()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getIntent().name()).isEqualTo("CHAT");
                    assertThat(trace.getStepCount()).isEqualTo(3);
                });
    }

    @Test
    void highRiskChatPersistsReportTraceAndTriggersToolChainAfterSse() {
        String body = postChat("student", "student123", "我不想活了，想伤害自己，今晚可能撑不住。");

        assertThat(body)
                .contains("event:meta")
                .contains("event:token")
                .contains("event:done")
                .contains("先确保安全")
                .doesNotContain("风险等级")
                .doesNotContain("Excel")
                .doesNotContain("MCP")
                .doesNotContain("报告");

        assertThat(reportRepository.findAll()).singleElement()
                .satisfies(report -> {
                    assertThat(report.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
                    assertThat(report.getIntent().name()).isEqualTo("RISK");
                });
        assertThat(traceRepository.findAll()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
                    assertThat(trace.getStepCount()).isEqualTo(5);
                });

        ArgumentCaptor<Long> reportId = ArgumentCaptor.forClass(Long.class);
        verify(toolOrchestrationService).handleAsync(reportId.capture());
        assertThat(reportRepository.findById(reportId.getValue())).isPresent();
    }

    @Test
    void adminAccountCannotStartStudentChat() {
        webTestClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth("admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"message":"我想用管理员账号发起聊天"}
                        """)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void consultStreamsAuthoritativeSourcesAndPersistsThemWithAnswer() {
        when(knowledgeService.retrieve(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new SearchResult(42L, "campus-guide.md", "可联系学校心理中心。", 0.9)));
        when(aiClient.stream(anyList())).thenReturn(Flux.just("引用验证回复：可联系学校心理中心 [1]。"));

        String body = postChat("student", "student123", "最近焦虑，想知道如何求助。");

        assertThat(body).contains("检索参考来源", "[1] campus-guide.md", "片段 #42");
        assertThat(messageRepository.findAll().stream()
                .filter(message -> message.getRole() == MessageRole.ASSISTANT)
                .filter(message -> message.getContent().startsWith("引用验证回复")))
                .singleElement().satisfies(message -> assertThat(message.getContent())
                        .contains("[1] campus-guide.md", "片段 #42"));
        ArgumentCaptor<List<AiMessage>> prompts = ArgumentCaptor.forClass(List.class);
        verify(aiClient).stream(prompts.capture());
        assertThat(prompts.getValue()).anySatisfy(message -> assertThat(message.content())
                .contains("\"citation\":\"[1]\"", "\"chunkId\":42"));
    }

    @Test
    void failedStreamDoesNotAppendSourcesToPartialAnswer() {
        when(knowledgeService.retrieve(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new SearchResult(43L, "unused-guide.md", "学校心理中心", 0.9)));
        when(aiClient.stream(anyList())).thenReturn(Flux.concat(
                Flux.just("未完成的回复"), Flux.error(new IllegalStateException("unavailable"))));

        String body = postChat("student", "student123", "最近焦虑，想知道如何求助。");

        assertThat(body).contains("event:error", "event:done").doesNotContain("检索参考来源", "unused-guide.md");
    }

    @Test
    void noEvidenceStillGivesExplicitUncertaintyWhenModelOmitsIt() {
        when(aiClient.stream(anyList())).thenReturn(Flux.just("证据不足验证回复：先和信任的人聊聊。"));

        String body = postChat("student", "student123", "最近焦虑，学校心理中心如何预约？");

        assertThat(body).contains("检索证据不足", "一般支持建议").doesNotContain("检索参考来源");
        assertThat(messageRepository.findAll().stream()
                .filter(message -> message.getRole() == MessageRole.ASSISTANT)
                .filter(message -> message.getContent().startsWith("证据不足验证回复")))
                .singleElement().satisfies(message -> assertThat(message.getContent()).contains("检索证据不足"));
    }

    @Test
    void matchingDocumentIsNotAutomaticallyConsideredSufficient() {
        when(knowledgeService.retrieve(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(
                new SearchResult(44L, "general-guide.md", "可与信任的人交流。", 0.9)));
        when(aiClient.complete(anyList())).thenAnswer(invocation -> {
            List<AiMessage> messages = invocation.getArgument(0);
            return messages.get(0).content().contains("判断检索结果是否足以")
                    ? "INSUFFICIENT" : scriptedAiClient.complete(messages);
        });
        when(aiClient.stream(anyList())).thenReturn(Flux.just("部分证据验证回复：先和信任的人聊聊。"));

        String body = postChat("student", "student123", "最近焦虑，学校心理中心几点开门？");

        assertThat(body).contains("检索证据不足", "general-guide.md", "片段 #44");
        ArgumentCaptor<List<AiMessage>> prompts = ArgumentCaptor.forClass(List.class);
        verify(aiClient).stream(prompts.capture());
        assertThat(prompts.getValue()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("本轮检索证据不足", "高风险时优先当前安全");
        });
    }

    @Test
    void studentCanEndOwnedSessionAndFlushPendingProfileMemory() {
        ChatSession session = createSession("student");
        when(userProfileMemoryService.rememberConversation(any(UserAccount.class), eq(session.getPublicId()),
                eq(null), eq(""), eq(true))).thenReturn(true);

        webTestClient.post().uri("/api/chat/sessions/{sessionId}/end", session.getPublicId())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isOk();

        verify(userProfileMemoryService).rememberConversation(any(UserAccount.class), eq(session.getPublicId()),
                eq(null), eq(""), eq(true));
        assertThat(sessionRepository.findById(session.getId())).isPresent();
    }

    @Test
    void sessionEndRejectsOtherOwnersAndAdminAccounts() {
        ChatSession otherSession = createSession("admin");
        webTestClient.post().uri("/api/chat/sessions/{sessionId}/end", otherSession.getPublicId())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isNotFound();
        webTestClient.post().uri("/api/chat/sessions/{sessionId}/end", otherSession.getPublicId())
                .headers(headers -> headers.setBasicAuth("admin", "admin123"))
                .exchange().expectStatus().isForbidden();
        verify(userProfileMemoryService, never()).rememberConversation(any(), anyString(), any(), anyString(), eq(true));
    }

    @Test
    void failedSessionEndCanBeRetriedWithoutDeletingConversation() {
        ChatSession session = createSession("student");
        when(userProfileMemoryService.rememberConversation(any(UserAccount.class), eq(session.getPublicId()),
                eq(null), eq(""), eq(true))).thenReturn(false);
        webTestClient.post().uri("/api/chat/sessions/{sessionId}/end", session.getPublicId())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isEqualTo(503);
        assertThat(sessionRepository.findById(session.getId())).isPresent();
    }

    private ChatSession createSession(String username) {
        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString());
        session.setUser(userRepository.findByUsername(username).orElseThrow());
        return sessionRepository.save(session);
    }

    private String postChat(String username, String password, String message) {
        EntityExchangeResult<String> result = webTestClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth(username, password))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"message":"%s"}
                        """.formatted(message))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();
        return result.getResponseBody() == null ? "" : result.getResponseBody();
    }
}
