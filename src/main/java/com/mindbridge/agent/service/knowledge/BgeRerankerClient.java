package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** Calls the batch scoring endpoint and validates its ordered response. */
@Component
public class BgeRerankerClient {

    private final WebClient webClient;
    private final Duration timeout;

    public BgeRerankerClient(MindBridgeProperties properties, WebClient.Builder webClientBuilder) {
        MindBridgeProperties.Knowledge config = properties.getKnowledge();
        this.webClient = webClientBuilder.baseUrl(config.getRerankerBaseUrl()).build();
        this.timeout = Duration.ofSeconds(config.getRerankerTimeoutSeconds());
    }

    public List<Double> score(String query, List<String> passages) {
        RerankResponse response = webClient.post()
                .uri("/rerank")
                .bodyValue(new RerankRequest(query, passages))
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .timeout(timeout)
                .block();
        if (response == null || !response.success() || response.scores() == null
                || response.scores().size() != passages.size()) {
            throw new IllegalStateException("BGE reranker returned incomplete scores");
        }
        List<Double> scores = new ArrayList<>(passages.size());
        for (int index = 0; index < passages.size(); index++) {
            Score item = response.scores().get(index);
            if (item == null || item.index() == null || item.index() != index
                    || item.score() == null || !Double.isFinite(item.score())) {
                throw new IllegalStateException("BGE reranker returned invalid scores");
            }
            scores.add(item.score());
        }
        return List.copyOf(scores);
    }

    private record RerankRequest(String query, List<String> passages) {
    }

    private record RerankResponse(boolean success, String model, List<Score> scores) {
    }

    private record Score(Integer index, Double score) {
    }
}
