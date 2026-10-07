package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ChatMessageRepository;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.PrivacySanitizer;
import com.mindbridge.agent.service.PsychologicalAssessmentService;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentRuntimeService;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.CompanionAgent;
import com.mindbridge.agent.service.agent.CounselorAgent;
import com.mindbridge.agent.service.agent.KnowledgeAgent;
import com.mindbridge.agent.service.agent.MemoryAgent;
import com.mindbridge.agent.service.agent.RiskGuardianAgent;
import com.mindbridge.agent.service.agent.SupervisorAgent;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentLoopHarnessTests {

    private ScriptedAiClient aiClient;
    private KnowledgeService knowledgeService;
    private AgentRuntimeService runtimeService;

    @BeforeEach
    void setUp() {
        aiClient = new ScriptedAiClient();
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getKnowledge().setTopK(2);
        properties.getKnowledge().setRerankerEnabled(false);

        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        ShortTermMemoryService shortTermMemoryService = mock(ShortTermMemoryService.class);
        UserProfileMemoryService userProfileMemoryService = mock(UserProfileMemoryService.class);
        knowledgeService = mock(KnowledgeService.class);

        when(shortTermMemoryService.recent(anyString())).thenReturn(List.of());
        when(userProfileMemoryService.profileBrief(any(UserAccount.class), anyString()))
                .thenReturn("无已保存用户画像。");
        when(knowledgeService.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new SearchResult(
                        1L,
                        "risk-policy.md",
                        "HIGH immediate danger should prioritize safety, trusted people and school counseling center.",
                        0.95),
                new SearchResult(
                        2L,
                        "campus-mental-health.md",
                        "焦虑和失眠可以使用呼吸、五感着陆、睡眠作息和学校心理中心资源。",
                        0.87)));

        MemoryAgent memoryAgent = new MemoryAgent(
                chatMessageRepository,
                shortTermMemoryService,
                properties,
                new PrivacySanitizer(),
                aiClient,
                userProfileMemoryService);
        SupervisorAgent supervisorAgent = new SupervisorAgent(new IntentClassifier(aiClient));
        KnowledgeAgent knowledgeAgent = new KnowledgeAgent(knowledgeService, properties, aiClient);
        RiskGuardianAgent riskGuardianAgent = new RiskGuardianAgent(
                new PsychologicalAssessmentService(aiClient, new ObjectMapper()));
        CompanionAgent companionAgent = new CompanionAgent(aiClient);
        CounselorAgent counselorAgent = new CounselorAgent(aiClient);

        runtimeService = new AgentRuntimeService(
                memoryAgent,
                supervisorAgent,
                knowledgeAgent,
                riskGuardianAgent,
                companionAgent,
                counselorAgent);
    }

    @Test
    void chatRouteSkipsRagAndRiskGuardian() {
        AgentRunResult result = run("帮我解释一下 Java 多线程。");

        assertThat(result.intent()).isEqualTo(IntentType.CHAT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.requiresReport()).isFalse();
        assertThat(result.retrievedKnowledge()).isEmpty();
        assertThat(result.responseAgent()).isEqualTo(AgentName.COMPANION_AGENT);
        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.COMPANION_AGENT);
    }

    @Test
    void consultRouteUsesKnowledgeRiskGuardianAndCounselor() {
        AgentRunResult result = run("我最近很焦虑，晚上总是睡不着。");

        assertThat(result.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.requiresReport()).isTrue();
        assertThat(result.retrievedKnowledge()).extracting(SearchResult::source)
                .contains("campus-mental-health.md");
        assertThat(result.responseAgent()).isEqualTo(AgentName.COUNSELOR_AGENT);
        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COUNSELOR_AGENT);
    }

    @Test
    void highRiskRouteEscalatesToHighAndKeepsCounselorPath() {
        AgentRunResult result = run("我不想活了，想伤害自己，今晚可能撑不住。");

        assertThat(result.intent()).isEqualTo(IntentType.RISK);
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.assessment().risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.requiresReport()).isTrue();
        assertThat(result.responseAgent()).isEqualTo(AgentName.COUNSELOR_AGENT);
        assertThat(agentNames(result)).containsExactly(
                AgentName.MEMORY_AGENT,
                AgentName.SUPERVISOR_AGENT,
                AgentName.KNOWLEDGE_AGENT,
                AgentName.RISK_GUARDIAN_AGENT,
                AgentName.COUNSELOR_AGENT);
        assertThat(result.steps()).hasSize(5);
        assertThat(result.steps().get(0).observation()).contains("memoryLoaded=true");
        assertThat(result.steps().get(2).observation()).contains("riskAssessed=false");
        assertThat(result.steps().get(3).observation()).contains("riskAssessed=true");
        assertThat(result.steps().get(4).observation()).contains("responsePlanned=true");
    }

    private AgentRunResult run(String input) {
        return runtimeService.run(user(), session(), input, input);
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setUsername("student");
        user.setDisplayName("Demo Student");
        return user;
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setPublicId("harness-session");
        session.setTitle("Harness session");
        return session;
    }

    private List<AgentName> agentNames(AgentRunResult result) {
        return result.steps().stream()
                .map(AgentStep::agent)
                .toList();
    }
}
