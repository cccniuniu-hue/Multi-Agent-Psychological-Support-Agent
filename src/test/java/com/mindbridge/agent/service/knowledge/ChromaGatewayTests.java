package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

class ChromaGatewayTests {

    private static final String COLLECTION_ID = "11111111-1111-1111-1111-111111111111";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ChromaGateway gateway;
    private boolean failUpsert;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();

        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getKnowledge().setUseChroma(true);
        properties.getKnowledge().setChromaBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getKnowledge().setChromaCollection("mindbridge_knowledge");
        gateway = new ChromaGateway(properties, WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void usesV2CollectionIdForUpsertQueryAndDelete() throws Exception {
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", 42L);
        chunk.setSource("guide.md");
        chunk.setSourceIndex(3);
        chunk.setContent("支持性倾听与情绪识别");
        chunk.setEmbeddingModel("text-embedding-test");
        chunk.setEmbeddingDimensions(3);
        List<Double> embedding = List.of(0.1, 0.2, 0.3);

        assertThat(gateway.mirror(chunk, embedding)).isTrue();
        List<SearchResult> results = gateway.query(embedding, "text-embedding-test", 5);
        gateway.deleteSource("guide.md");

        assertThat(requests).extracting(CapturedRequest::method).containsOnly("POST");
        assertThat(requests).extracting(CapturedRequest::path).containsExactly(
                "/api/v2/tenants/default_tenant/databases/default_database/collections",
                "/api/v2/tenants/default_tenant/databases/default_database/collections/" + COLLECTION_ID + "/upsert",
                "/api/v2/tenants/default_tenant/databases/default_database/collections/" + COLLECTION_ID + "/query",
                "/api/v2/tenants/default_tenant/databases/default_database/collections/" + COLLECTION_ID + "/delete");

        JsonNode createBody = objectMapper.readTree(requests.get(0).body());
        assertThat(createBody.path("name").asText()).isEqualTo("mindbridge_knowledge");
        assertThat(createBody.path("get_or_create").asBoolean()).isTrue();

        JsonNode upsertBody = objectMapper.readTree(requests.get(1).body());
        assertThat(upsertBody.path("ids").path(0).asText()).isEqualTo("42");
        assertThat(upsertBody.path("documents").path(0).asText()).isEqualTo("支持性倾听与情绪识别");
        assertThat(upsertBody.path("embeddings").path(0)).hasSize(3);
        assertThat(upsertBody.path("embeddings").path(0).path(1).asDouble()).isEqualTo(0.2);
        assertThat(upsertBody.path("metadatas").path(0).path("source").asText()).isEqualTo("guide.md");
        assertThat(upsertBody.path("metadatas").path(0).path("embeddingModel").asText())
                .isEqualTo("text-embedding-test");
        assertThat(upsertBody.path("metadatas").path(0).path("embeddingDimensions").asInt()).isEqualTo(3);

        JsonNode queryBody = objectMapper.readTree(requests.get(2).body());
        assertThat(queryBody.has("query_texts")).isFalse();
        assertThat(queryBody.path("query_embeddings").path(0)).hasSize(3);
        assertThat(queryBody.path("query_embeddings").path(0).path(2).asDouble()).isEqualTo(0.3);
        assertThat(queryBody.path("n_results").asInt()).isEqualTo(5);
        assertThat(queryBody.path("where").path("$and").path(0).path("embeddingModel").asText())
                .isEqualTo("text-embedding-test");
        assertThat(queryBody.path("where").path("$and").path(1).path("embeddingDimensions").asInt())
                .isEqualTo(3);

        JsonNode deleteBody = objectMapper.readTree(requests.get(3).body());
        assertThat(deleteBody.path("where").path("source").asText()).isEqualTo("guide.md");
        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.chunkId()).isEqualTo(42L);
            assertThat(result.source()).isEqualTo("guide.md");
            assertThat(result.content()).isEqualTo("支持性倾听与情绪识别");
            assertThat(result.score()).isEqualTo(0.8);
        });
    }

    @Test
    void skipsChromaRequestsWhenEmbeddingIsUnavailable() {
        KnowledgeChunk chunk = new KnowledgeChunk();

        assertThat(gateway.mirror(chunk, List.of())).isFalse();
        assertThat(gateway.query(List.of(), "text-embedding-test", 5)).isEmpty();

        assertThat(requests).isEmpty();
    }

    @Test
    void reportsUpsertFailureForRetryWithoutDeletingExistingIndex() {
        failUpsert = true;
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", 42L);
        chunk.setSource("guide.md");
        chunk.setContent("支持性倾听");
        chunk.setEmbeddingModel("text-embedding-test");
        chunk.setEmbeddingDimensions(3);

        assertThat(gateway.mirror(chunk, List.of(0.1, 0.2, 0.3))).isFalse();
        assertThat(requests).extracting(CapturedRequest::path).noneMatch(path -> path.endsWith("/delete"));
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new CapturedRequest(exchange.getRequestMethod(), path, requestBody));

        if (failUpsert && path.endsWith("/upsert")) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
        }

        String responseBody;
        if (path.endsWith("/collections")) {
            responseBody = "{\"id\":\"" + COLLECTION_ID + "\",\"name\":\"mindbridge_knowledge\"}";
        } else if (path.endsWith("/query")) {
            responseBody = "{\"ids\":[[\"42\"]],\"documents\":[[\"支持性倾听与情绪识别\"]],"
                    + "\"metadatas\":[[{\"source\":\"guide.md\"}]],\"distances\":[[0.2]]}";
        } else {
            responseBody = "{}";
        }

        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String body) {
    }
}
