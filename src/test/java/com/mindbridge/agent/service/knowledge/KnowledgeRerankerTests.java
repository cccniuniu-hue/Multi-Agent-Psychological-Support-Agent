package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeRerankerTests {

    @Test
    void defaultsToFiftyCandidatesAndFiveResults() {
        MindBridgeProperties.Knowledge config = new MindBridgeProperties().getKnowledge();
        assertThat(config.getCoarseRecallLimit()).isEqualTo(50);
        assertThat(config.getRerankerCandidateLimit()).isEqualTo(50);
        assertThat(config.getTopK()).isEqualTo(5);
    }

    @Test
    void reranksFiftyCandidatesByRawBgeScoreAndReturnsTopFive() {
        BgeRerankerClient client = mock(BgeRerankerClient.class);
        List<Double> scores = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            scores.add((double) -index);
        }
        scores.set(49, 10.0);
        when(client.score(eq("焦虑睡眠"), anyList())).thenReturn(scores);

        List<SearchResult> results = new KnowledgeReranker(properties(), client)
                .rerank("焦虑睡眠", candidates(50), 5);

        assertThat(results).extracting(SearchResult::chunkId)
                .containsExactly(50L, 1L, 2L, 3L, 4L);
        assertThat(results.get(0).score()).isEqualTo(10.0);
        verify(client).score(eq("焦虑睡眠"), org.mockito.ArgumentMatchers.argThat(
                passages -> passages.size() == 50 && passages.get(49).equals("候选50")));
    }

    @Test
    void fallsBackToInitialTopFiveWhenServiceFails() {
        BgeRerankerClient client = mock(BgeRerankerClient.class);
        when(client.score(eq("q"), anyList())).thenThrow(new IllegalStateException("service unavailable"));

        List<SearchResult> results = new KnowledgeReranker(properties(), client)
                .rerank("q", candidates(50), 5);

        assertThat(results).extracting(SearchResult::chunkId)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void disabledRerankerKeepsInitialOrderWithoutHttpRequest() {
        MindBridgeProperties properties = properties();
        properties.getKnowledge().setRerankerEnabled(false);
        BgeRerankerClient client = mock(BgeRerankerClient.class);

        List<SearchResult> results = new KnowledgeReranker(properties, client)
                .rerank("q", candidates(50), 5);

        assertThat(results).extracting(SearchResult::chunkId)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        verifyNoInteractions(client);
    }

    private MindBridgeProperties properties() {
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getKnowledge().setRerankerEnabled(true);
        properties.getKnowledge().setRerankerCandidateLimit(50);
        return properties;
    }

    private List<SearchResult> candidates(int count) {
        List<SearchResult> candidates = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            candidates.add(new SearchResult((long) index + 1, "guide.md",
                    "候选" + (index + 1), 1.0 - index * 0.01));
        }
        return candidates;
    }
}
