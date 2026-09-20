package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
/**
 * Chroma 向量库网关。
 *
 * <p>当 use-chroma=true 时，把知识库切块镜像到外部向量库，并优先从 Chroma 检索。</p>
 */
public class ChromaGateway {

    private static final String COLLECTIONS_PATH =
            "/api/v2/tenants/{tenant}/databases/{database}/collections";

    private final MindBridgeProperties properties;
    private final WebClient webClient;
    private volatile String collectionId;

    public ChromaGateway(MindBridgeProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(properties.getKnowledge().getChromaBaseUrl()).build();
    }

    public boolean mirror(KnowledgeChunk chunk, List<Double> embedding) {
        if (!properties.getKnowledge().isUseChroma()
                || embedding == null
                || embedding.isEmpty()
                || chunk.getEmbeddingModel() == null
                || chunk.getEmbeddingModel().isBlank()
                || chunk.getEmbeddingDimensions() == null
                || chunk.getEmbeddingDimensions() != embedding.size()) {
            return false;
        }
        // 本地数据库仍是主存储；Chroma 只是可选检索加速层。
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return false;
        }
        Map<String, Object> body = Map.of(
                "ids", List.of(String.valueOf(chunk.getId())),
                "documents", List.of(chunk.getContent()),
                "embeddings", List.of(embedding),
                "metadatas", List.of(Map.of(
                        "source", chunk.getSource(),
                        "sourceIndex", chunk.getSourceIndex(),
                        "embeddingModel", chunk.getEmbeddingModel(),
                        "embeddingDimensions", chunk.getEmbeddingDimensions()))
        );
        try {
            webClient.post()
                    .uri(COLLECTIONS_PATH + "/{collectionId}/upsert",
                            properties.getKnowledge().getChromaTenant(),
                            properties.getKnowledge().getChromaDatabase(),
                            ensuredCollectionId)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception ignored) {
            // 日常入库仍可回退到数据库；重建命令通过返回值报告失败。
            return false;
        }
    }

    public List<SearchResult> query(List<Double> embedding, String embeddingModel, int topK) {
        if (!properties.getKnowledge().isUseChroma()
                || embedding == null
                || embedding.isEmpty()
                || embeddingModel == null
                || embeddingModel.isBlank()) {
            return List.of();
        }
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "query_embeddings", List.of(embedding),
                "n_results", topK,
                "where", Map.of("$and", List.of(
                        Map.of("embeddingModel", embeddingModel),
                        Map.of("embeddingDimensions", embedding.size()))),
                "include", List.of("documents", "metadatas", "distances")
        );
        try {
            JsonNode response = webClient.post()
                    .uri(COLLECTIONS_PATH + "/{collectionId}/query",
                            properties.getKnowledge().getChromaTenant(),
                            properties.getKnowledge().getChromaDatabase(),
                            ensuredCollectionId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return parseResults(response);
        } catch (Exception ignored) {
            // 外部向量库不可用时返回空结果，让 KnowledgeService 回退到本地检索。
            return List.of();
        }
    }

    public void deleteSource(String source) {
        if (!properties.getKnowledge().isUseChroma()) {
            return;
        }
        String ensuredCollectionId = ensureCollection();
        if (ensuredCollectionId == null) {
            return;
        }
        Map<String, Object> body = Map.of("where", Map.of("source", source));
        webClient.post()
                .uri(COLLECTIONS_PATH + "/{collectionId}/delete",
                        properties.getKnowledge().getChromaTenant(),
                        properties.getKnowledge().getChromaDatabase(),
                        ensuredCollectionId)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorComplete()
                .block();
    }

    private List<SearchResult> parseResults(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        JsonNode docs = response.path("documents").path(0);
        JsonNode ids = response.path("ids").path(0);
        JsonNode metadatas = response.path("metadatas").path(0);
        JsonNode distances = response.path("distances").path(0);
        for (int i = 0; i < docs.size(); i++) {
            Long id = parseId(ids.path(i).asText());
            double score = 1.0 - distances.path(i).asDouble(1.0);
            String source = metadatas.path(i).path("source").asText("chroma");
            results.add(new SearchResult(id, source, docs.path(i).asText(), score));
        }
        return results;
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
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
                            properties.getKnowledge().getChromaTenant(),
                            properties.getKnowledge().getChromaDatabase())
                    .bodyValue(Map.of(
                            "name", properties.getKnowledge().getChromaCollection(),
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
}
