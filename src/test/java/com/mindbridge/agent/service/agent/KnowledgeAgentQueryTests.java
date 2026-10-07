package com.mindbridge.agent.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeAgentQueryTests {

    @Test
    void usesOriginalQuestionForSimpleConsultationAndRisk() {
        for (IntentType intent : List.of(IntentType.CONSULT, IntentType.RISK)) {
            String question = intent == IntentType.CONSULT
                    ? "最近有点焦虑，怎么预约心理中心？"
                    : "我今晚很想伤害自己，也不知道还能向谁求助，请帮我联系可信任的人和学校心理中心。";
            Fixture fixture = new Fixture();
            AgentContext context = fixture.context(intent, question);

            fixture.agent.act(context);

            verify(fixture.knowledgeService).retrieve(question, 5);
            assertThat(context.knowledgeQuery()).isEqualTo(question);
            assertThat(fixture.rewriteCalls).isZero();
        }
    }

    @Test
    void skipsRewriteWhenOriginalEvidenceIsSufficient() {
        String question = "最近几周我既焦虑又睡不着，考试压力也很大，和室友相处一直紧张，想知道能如何调整以及到哪里求助。";
        Fixture fixture = new Fixture();
        AgentContext context = fixture.context(IntentType.CONSULT, question);

        fixture.agent.act(context);

        verify(fixture.knowledgeService).retrieve(question, 5);
        assertThat(context.knowledgeQuery()).isEqualTo(question);
        assertThat(fixture.rewriteCalls).isZero();
    }

    @Test
    void rejectsOverlongOrMultilineRewrite() {
        String question = "最近几周我既焦虑又睡不着，考试压力也很大，和室友相处一直紧张，想知道能如何调整以及到哪里求助。";
        for (String modelOutput : List.of("焦虑".repeat(30), "焦虑睡眠\n这是解释")) {
            Fixture fixture = new Fixture();
            doAnswer(invocation -> {
                List<AiMessage> messages = invocation.getArgument(0);
                return messages.get(0).content().contains("改写成适合检索")
                        ? modelOutput : "INSUFFICIENT";
            }).when(fixture.aiClient).complete(anyList());

            AgentContext context = fixture.context(IntentType.CONSULT, question);
            fixture.agent.act(context);

            verify(fixture.knowledgeService).retrieve(question, 5);
            assertThat(context.knowledgeQuery()).isEqualTo(question);
        }
    }

    @Test
    void timesOutSlowRewriteAndUsesOriginalQuestion() {
        String question = "最近几周我既焦虑又睡不着，考试压力也很大，和室友相处一直紧张，想知道能如何调整以及到哪里求助。";
        Fixture fixture = new Fixture();
        doAnswer(invocation -> {
            List<AiMessage> messages = invocation.getArgument(0);
            if (messages.get(0).content().contains("改写成适合检索")) {
                Thread.sleep(5000);
                return "迟到的检索词";
            }
            return "INSUFFICIENT";
        }).when(fixture.aiClient).complete(anyList());

        long start = System.nanoTime();
        fixture.agent.act(fixture.context(IntentType.CONSULT, question));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(4500));
        verify(fixture.knowledgeService).retrieve(question, 5);
    }

    @Test
    void mergesOriginalAndRewrittenResultsWithoutDuplicatesOrThirdRecall() {
        String question = "最近几周我既焦虑又睡不着，考试压力也很大，和室友相处一直紧张，想知道能如何调整以及到哪里求助。";
        Fixture fixture = new Fixture();
        doAnswer(invocation -> {
            List<AiMessage> messages = invocation.getArgument(0);
            return messages.get(0).content().contains("INSUFFICIENT")
                    ? "INSUFFICIENT" : "alternate query";
        }).when(fixture.aiClient).complete(anyList());
        when(fixture.knowledgeService.retrieve(anyString(), eq(5))).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            if (query.equals(question)) {
                return List.of(
                        new SearchResult(1L, "a.md", "original first", 0.9),
                        new SearchResult(2L, "b.md", "shared", 0.8),
                        new SearchResult(3L, "c.md", "original third", 0.7));
            }
            return List.of(
                    new SearchResult(2L, "b.md", "shared", 0.95),
                    new SearchResult(4L, "d.md", "rewritten fourth", 0.9),
                    new SearchResult(5L, "e.md", "rewritten fifth", 0.8));
        });
        AgentContext context = fixture.context(IntentType.CONSULT, question);

        fixture.agent.act(context);

        verify(fixture.knowledgeService).retrieve(question, 5);
        verify(fixture.knowledgeService).retrieve("alternate query", 5);
        verify(fixture.aiClient, org.mockito.Mockito.times(2)).complete(anyList());
        assertThat(context.retrievedKnowledge()).extracting(SearchResult::chunkId)
                .containsExactly(1L, 2L, 4L, 3L, 5L);
        assertThat(context.knowledgeQuery()).contains(question, "alternate query");
    }

    @Test
    void acceptsOnlyExactSufficiencyAnswer() {
        assertThat(KnowledgeAgent.isSufficientAnswer(" SUFFICIENT ")).isTrue();
        assertThat(KnowledgeAgent.isSufficientAnswer("INSUFFICIENT")).isFalse();
        assertThat(KnowledgeAgent.isSufficientAnswer("NOT SUFFICIENT")).isFalse();
        assertThat(KnowledgeAgent.isSufficientAnswer(null)).isFalse();
    }

    private static class Fixture {
        private final KnowledgeService knowledgeService = mock(KnowledgeService.class);
        private final AiClient aiClient = mock(AiClient.class);
        private final KnowledgeAgent agent;
        private int rewriteCalls;

        private Fixture() {
            MindBridgeProperties properties = new MindBridgeProperties();
            agent = new KnowledgeAgent(knowledgeService, properties, aiClient);
            when(knowledgeService.retrieve(anyString(), eq(5))).thenReturn(List.of(
                    new SearchResult(1L, "guide.md", "可联系学校心理中心。", 0.9)));
            when(aiClient.complete(anyList())).thenAnswer(invocation -> {
                List<AiMessage> messages = invocation.getArgument(0);
                if (messages.get(0).content().contains("改写成适合检索")) {
                    rewriteCalls++;
                    return "校园心理中心 焦虑睡眠 求助";
                }
                return "SUFFICIENT";
            });
        }

        private AgentContext context(IntentType intent, String question) {
            AgentContext context = new AgentContext(new UserAccount(), new ChatSession(), question, question);
            context.setIntent(intent);
            context.markIntentRouted();
            return context;
        }
    }
}
