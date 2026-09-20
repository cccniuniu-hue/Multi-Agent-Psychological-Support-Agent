package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.UserMemoryItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
/**
 * 用户画像记忆 Chroma 网关。
 *
 * <p>MySQL/H2 保存可审计的画像条目；Chroma 只保存语义检索索引，
 * 用于按当前输入召回相关长期记忆。</p>
 */
public class UserMemoryChromaGateway {

    private static final String COLLECTIONS_PATH =
            "/api/v2/tenants/{tenant}/databases/{database}/collections";

    private final MindBridgeProperties properties;
    private final WebClient webClient;
    private final MemoryEmbeddingClient embeddingClient;
    private volatile String collectionId;

    public UserMemoryChromaGateway(
            MindBridgeProperties properties,
            WebClient.Builder webClientBuilder,
            MemoryEmbeddingClient embeddingClient
    ) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(properties.getMemory().getChromaBaseUrl()).build();
        this.embeddingClient = embeddingClient;
    }

    public boolean mirror(UserMemoryItem item) {
        if (!properties.getMemory().isUseChroma() || item.getId() == null) {
            return false;
        }
        String text = document(item);
        List<Double> embedding = safeEmbedding(text);
        String model = embeddingClient.modelName();
        if (embedding == null || embedding.isEmpty() || model == null || model.isBlank()) {
            return false;
        }
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return false;
        }
        Map<String, Object> body = Map.of(
                "ids", List.of(chromaId(item.getId())),
                "documents", List.of(text),
                "embeddings", List.of(embedding),
                "metadatas", List.of(metadata(item, model, embedding.size()))
        );
        try {
            webClient.post()
                    .uri(COLLECTIONS_PATH + "/{collectionId}/upsert",
                            properties.getMemory().getChromaTenant(),
                            properties.getMemory().getChromaDatabase(),
                            ensuredCollectionId)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public List<UserMemoryMatch> query(Long userId, String text, int topK) {
        if (!properties.getMemory().isUseChroma() || userId == null || text == null || text.isBlank()) {
            return List.of();
        }
        List<Double> embedding = safeEmbedding(text);
        String model = embeddingClient.modelName();
        if (embedding == null || embedding.isEmpty() || model == null || model.isBlank()) {
            return List.of();
        }
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "query_embeddings", List.of(embedding),
                "n_results", Math.max(1, topK),
                "where", Map.of("$and", List.of(
                        Map.of("userId", String.valueOf(userId)),
                        Map.of("embeddingModel", model),
                        Map.of("embeddingDimensions", embedding.size()))),
                "include", List.of("metadatas", "distances")
        );
        try {
            JsonNode response = webClient.post()
                    .uri(COLLECTIONS_PATH + "/{collectionId}/query",
                            properties.getMemory().getChromaTenant(),
                            properties.getMemory().getChromaDatabase(),
                            ensuredCollectionId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return parseMatches(response);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public void delete(Long memoryId) {
        if (!properties.getMemory().isUseChroma() || memoryId == null) {
            return;
        }
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return;
        }
        webClient.post()
                .uri(COLLECTIONS_PATH + "/{collectionId}/delete",
                        properties.getMemory().getChromaTenant(),
                        properties.getMemory().getChromaDatabase(),
                        ensuredCollectionId)
                .bodyValue(Map.of("ids", List.of(chromaId(memoryId))))
                .retrieve()
                .toBodilessEntity()
                .onErrorComplete()
                .block();
    }

    private List<UserMemoryMatch> parseMatches(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<UserMemoryMatch> matches = new ArrayList<>();
        JsonNode metadatas = response.path("metadatas").path(0);
        JsonNode distances = response.path("distances").path(0);
        for (int i = 0; i < metadatas.size(); i++) {
            Long memoryId = parseLong(metadatas.path(i).path("memoryId").asText());
            if (memoryId != null) {
                double score = 1.0 - distances.path(i).asDouble(1.0);
                matches.add(new UserMemoryMatch(memoryId, score));
            }
        }
        return matches;
    }

    private Map<String, Object> metadata(UserMemoryItem item, String model, int dimensions) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memoryId", String.valueOf(item.getId()));
        metadata.put("userId", String.valueOf(item.getUser().getId()));
        metadata.put("type", item.getType().name());
        metadata.put("embeddingModel", model);
        metadata.put("embeddingDimensions", dimensions);
        ChatSession session = item.getSourceSession();
        if (session != null && session.getPublicId() != null) {
            metadata.put("sourceSessionId", session.getPublicId());
        }
        return metadata;
    }

    private String document(UserMemoryItem item) {
        String evidence = item.getEvidence() == null || item.getEvidence().isBlank()
                ? ""
                : "\n证据：" + item.getEvidence();
        return "%s：%s%s".formatted(item.getType().name(), item.getSummary(), evidence);
    }

    private List<Double> safeEmbedding(String text) {
        try {
            return embeddingClient.embed(text);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String chromaId(Long memoryId) {
        return "memory:" + memoryId;
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private synchronized String ensureCollection() {
        if (collectionId != null) {
            return collectionId;
        }
        try {
            JsonNode response = webClient.post()
                    .uri(COLLECTIONS_PATH,
                            properties.getMemory().getChromaTenant(),
                            properties.getMemory().getChromaDatabase())
                    .bodyValue(Map.of(
                            "name", properties.getMemory().getChromaCollection(),
                            "get_or_create", true))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            String resolvedId = response == null ? null : response.path("id").asText(null);
            if (resolvedId == null || resolvedId.isBlank()) {
                return null;
            }
            collectionId = resolvedId;
            return resolvedId;
        } catch (Exception ignored) {
            return null;
        }
    }

    public record UserMemoryMatch(Long memoryId, double score) {
    }
}
