package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class MarkerDocumentClientTests {

    private HttpServer server;
    private String requestBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/marker/upload", this::handleUpload);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void uploadsSupportedDocumentAndMapsMarkdownAndImages() {
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getKnowledge().setMarkerEnabled(true);
        properties.getKnowledge().setMarkerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getKnowledge().setMarkerTimeoutSeconds(2);
        MarkerDocumentClient client = new MarkerDocumentClient(properties, WebClient.builder());

        DocumentParseResult result = client.parse("guide.docx", "docx bytes".getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(requestBody).contains("filename=\"guide.docx\"", "docx bytes");
        assertThat(result.source()).isEqualTo("guide.docx");
        assertThat(result.title()).isEqualTo("guide");
        assertThat(result.body()).isEqualTo("# Guide\nUseful advice");
        assertThat(result.pages()).containsExactly(new DocumentParseResult.Page(1, "# Guide\nUseful advice"));
        assertThat(result.images()).containsExactly(new DocumentParseResult.ImageReference("images/chart.png", null));
        assertThat(result.status()).isEqualTo(DocumentParseResult.Status.SUCCESS);
    }

    private void handleUpload(HttpExchange exchange) throws IOException {
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] response = """
                {"success":true,"source":"guide.docx","title":"guide","output":"# Guide\\nUseful advice","images":{"images/chart.png":"base64"}}
                """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
