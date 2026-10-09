package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.AgentRunTraceRepository;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.service.ToolOrchestrationService;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.List;
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
