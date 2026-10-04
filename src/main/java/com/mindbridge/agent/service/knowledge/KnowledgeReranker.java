package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/** Reranks the initial retrieval candidates with BGE and preserves initial order on failure. */
@Component
public class KnowledgeReranker {

    private final MindBridgeProperties.Knowledge config;
    private final BgeRerankerClient client;

    public KnowledgeReranker(MindBridgeProperties properties, BgeRerankerClient client) {
        this.config = properties.getKnowledge();
        this.client = client;
    }

    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        if (topK <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        if (!config.isRerankerEnabled() || candidates.size() <= 1) {
            return fallback(candidates, topK);
        }

        int limit = Math.min(50, Math.max(topK, Math.max(1, config.getRerankerCandidateLimit())));
        List<SearchResult> selected = candidates.stream().limit(limit).toList();
        try {
            List<String> passages = selected.stream().map(candidate -> clip(candidate.content())).toList();
            List<Double> scores = client.score(query, passages);
            if (scores == null || scores.size() != selected.size()
                    || scores.stream().anyMatch(score -> score == null || !Double.isFinite(score))) {
                return fallback(candidates, topK);
            }
            return IntStream.range(0, selected.size())
                    .mapToObj(index -> new SearchResult(selected.get(index).chunkId(),
                            selected.get(index).source(), selected.get(index).content(), scores.get(index)))
                    .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                    .limit(topK)
                    .toList();
        } catch (RuntimeException exception) {
            return fallback(candidates, topK);
        }
    }

    private String clip(String content) {
        if (content == null) {
            return "";
        }
        int maxChars = Math.max(1, Math.min(4000, config.getRerankerMaxContentChars()));
        return content.length() <= maxChars ? content : content.substring(0, maxChars);
    }

    private List<SearchResult> fallback(List<SearchResult> candidates, int topK) {
        return candidates.stream().limit(topK).toList();
    }
}
