package com.mindbridge.agent.service.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.domain.UserMemoryItem;
import com.mindbridge.agent.domain.UserMemoryType;
import com.mindbridge.agent.service.memory.UserMemoryChromaGateway.UserMemoryMatch;
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

class UserMemoryChromaGatewayTests {

    private static final String COLLECTION_ID = "22222222-2222-2222-2222-222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private UserMemoryChromaGateway gateway;
    private MindBridgeProperties properties;
    private boolean failUpsert;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();

        properties = new MindBridgeProperties();
        properties.getMemory().setUseChroma(true);
        properties.getMemory().setChromaBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMemory().setChromaCollection("mindbridge_user_memory");
        gateway = new UserMemoryChromaGateway(properties, WebClient.builder(), new TestMemoryEmbeddingClient());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void usesV2CollectionIdAndKeepsUserFilterOnQuery() throws Exception {
        UserAccount user = new UserAccount();
        ReflectionTestUtils.setField(user, "id", 7L);

        UserMemoryItem item = new UserMemoryItem();
        ReflectionTestUtils.setField(item, "id", 23L);
        item.setUser(user);
        item.setType(UserMemoryType.SUPPORT_NEED);
        item.setSummary("考试前需要先梳理任务优先级");
        item.setEvidence("最近两次备考时都这样更安心");

        assertThat(gateway.mirror(item)).isTrue();
        List<UserMemoryMatch> matches = gateway.query(7L, "最近复习压力很大", 4);
        gateway.delete(23L);

        assertThat(requests).extracting(CapturedRequest::method).containsOnly("POST");
        assertThat(requests).extracting(CapturedRequest::path).containsExactly(
                "/api/v2/tenants/default_tenant/databases/default_database/collections",
                "/api/v2/tenants/default_tenant/databases/default_database/collections/" + COLLECTION_ID + "/upsert",
                "/api/v2/tenants/default_tenant/databases/default_database/collections/" + COLLECTION_ID + "/query",
                "/api/v2/tenants/default_tenant/databases/default_database/collections/" + COLLECTION_ID + "/delete");

        JsonNode createBody = objectMapper.readTree(requests.get(0).body());
        assertThat(createBody.path("name").asText()).isEqualTo("mindbridge_user_memory");
        assertThat(createBody.path("get_or_create").asBoolean()).isTrue();

        JsonNode upsertBody = objectMapper.readTree(requests.get(1).body());
        assertThat(upsertBody.path("ids").path(0).asText()).isEqualTo("memory:23");
        assertThat(upsertBody.path("embeddings").path(0).path(1).asDouble()).isEqualTo(0.2);
        assertThat(upsertBody.path("metadatas").path(0).path("memoryId").asText()).isEqualTo("23");
        assertThat(upsertBody.path("metadatas").path(0).path("userId").asText()).isEqualTo("7");
        assertThat(upsertBody.path("metadatas").path(0).path("embeddingModel").asText()).isEqualTo("private-test-model");
        assertThat(upsertBody.path("metadatas").path(0).path("embeddingDimensions").asInt()).isEqualTo(3);

        JsonNode queryBody = objectMapper.readTree(requests.get(2).body());
        assertThat(queryBody.has("query_texts")).isFalse();
        assertThat(queryBody.path("query_embeddings").path(0).path(2).asDouble()).isEqualTo(0.3);
        assertThat(queryBody.path("n_results").asInt()).isEqualTo(4);
        assertThat(queryBody.path("where").path("$and").path(0).path("userId").asText()).isEqualTo("7");
        assertThat(queryBody.path("where").path("$and").path(1).path("embeddingModel").asText())
                .isEqualTo("private-test-model");
        assertThat(queryBody.path("where").path("$and").path(2).path("embeddingDimensions").asInt())
                .isEqualTo(3);

        JsonNode deleteBody = objectMapper.readTree(requests.get(3).body());
        assertThat(deleteBody.path("ids").path(0).asText()).isEqualTo("memory:23");
        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.memoryId()).isEqualTo(23L);
            assertThat(match.score()).isEqualTo(0.85);
        });
    }

    @Test
    void defaultConfigurationNeverSendsProfileTextToChroma() {
        properties.getEmbedding().setApiKey("knowledge-only-key");
        UserAccount user = new UserAccount();
        ReflectionTestUtils.setField(user, "id", 7L);
        UserMemoryItem item = new UserMemoryItem();
        ReflectionTestUtils.setField(item, "id", 23L);
        item.setUser(user);
        item.setType(UserMemoryType.SUPPORT_NEED);
        item.setSummary("敏感画像内容");
        gateway = new UserMemoryChromaGateway(properties, WebClient.builder(),
                new ConfiguredMemoryEmbeddingClient(properties, WebClient.builder()));

        assertThat(gateway.mirror(item)).isFalse();
        assertThat(gateway.query(7L, "敏感查询", 4)).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void embeddingFailureKeepsProfileIndexOptional() {
        UserAccount user = new UserAccount();
        ReflectionTestUtils.setField(user, "id", 7L);
        UserMemoryItem item = new UserMemoryItem();
        ReflectionTestUtils.setField(item, "id", 23L);
        item.setUser(user);
        item.setType(UserMemoryType.SUPPORT_NEED);
        item.setSummary("画像内容");
        gateway = new UserMemoryChromaGateway(properties, WebClient.builder(), new MemoryEmbeddingClient() {
            @Override
            public List<Double> embed(String text) {
                throw new IllegalStateException("embedding unavailable");
            }

            @Override
            public String modelName() {
                return "private-test-model";
            }
        });

        assertThat(gateway.mirror(item)).isFalse();
        assertThat(gateway.query(7L, "画像查询", 4)).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void reportsUpsertFailureSoRebuildCanStop() {
        failUpsert = true;
        UserAccount user = new UserAccount();
        ReflectionTestUtils.setField(user, "id", 7L);
        UserMemoryItem item = new UserMemoryItem();
        ReflectionTestUtils.setField(item, "id", 23L);
        item.setUser(user);
        item.setSummary("需要支持");

        assertThat(gateway.mirror(item)).isFalse();
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
            responseBody = "{\"id\":\"" + COLLECTION_ID + "\",\"name\":\"mindbridge_user_memory\"}";
        } else if (path.endsWith("/query")) {
            responseBody = "{\"ids\":[[\"memory:23\"]],\"metadatas\":[[{\"memoryId\":\"23\","
                    + "\"userId\":\"7\"}]],\"distances\":[[0.15]]}";
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

    private static class TestMemoryEmbeddingClient implements MemoryEmbeddingClient {
        @Override
        public List<Double> embed(String text) {
            return List.of(0.1, 0.2, 0.3);
        }

        @Override
        public String modelName() {
            return "private-test-model";
        }
    }
}
