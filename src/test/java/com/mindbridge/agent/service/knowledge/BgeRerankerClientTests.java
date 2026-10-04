package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class BgeRerankerClientTests {

    private HttpServer server;
    private MindBridgeProperties properties;
    private String requestBody;
    private int status = 200;
    private String response = """
            {"success":true,"model":"BAAI/bge-reranker-v2-m3","scores":[{"index":0,"score":-1.2},{"index":1,"score":3.4}]}
            """;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", this::handle);
        server.start();
        properties = new MindBridgeProperties();
        properties.getKnowledge().setRerankerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsBatchAndPreservesRawScoresInInputOrder() {
        assertThat(client().score("焦虑睡眠", List.of("第一段", "第二段")))
                .containsExactly(-1.2, 3.4);
        assertThat(requestBody).contains("焦虑睡眠", "第一段", "第二段");
    }

    @Test
    void rejectsIncompleteOrOutOfOrderScores() {
        response = """
                {"success":true,"scores":[{"index":1,"score":3.4},{"index":0,"score":-1.2}]}
                """;
        assertThatThrownBy(() -> client().score("q", List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class);
        response = """
                {"success":true,"scores":[{"index":0,"score":0.5}]}
                """;
        assertThatThrownBy(() -> client().score("q", List.of("a", "b")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportsHttpErrorsToCallerForFallback() {
        status = 502;
        assertThatThrownBy(() -> client().score("q", List.of("a")))
                .isInstanceOf(RuntimeException.class);
    }

    private BgeRerankerClient client() {
        return new BgeRerankerClient(properties, WebClient.builder());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
