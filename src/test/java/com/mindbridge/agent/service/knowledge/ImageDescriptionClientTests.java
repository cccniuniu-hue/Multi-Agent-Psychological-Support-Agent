package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ImageDescriptionClientTests {
    private HttpServer server;
    private MindBridgeProperties properties;
    private final AtomicInteger requests = new AtomicInteger();
    private String requestBody;
    private int status = 200;
    private String response = """
            {"success":true,"source":"chart.png","model":"test-qwen2-vl","description":{
              "image_type":"bar_chart","summary":"Exam week has the highest score.",
              "core_elements":["Week","Score"],"key_relations":["Score rises"],"data_insights":["Peak: 8"]}}
            """;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/qwen2-vl/describe", this::handle);
        server.start();
        properties = new MindBridgeProperties();
        properties.getKnowledge().setImageDescriptionEnabled(true);
        properties.getKnowledge().setImageDescriptionBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getKnowledge().setImageDescriptionTimeoutSeconds(1);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void uploadsDecodedImageAndMapsStructuredDescription() {
        ImageDescription description = client().describe("images/chart.png", "cG5nLWRhdGE=").orElseThrow();

        assertThat(requestBody).contains("filename=\"chart.png\"", "image/png", "png-data");
        assertThat(description.imageType()).isEqualTo("bar_chart");
        assertThat(description.summary()).isEqualTo("Exam week has the highest score.");
        assertThat(description.coreElements()).containsExactly("Week", "Score");
        assertThat(description.keyRelations()).containsExactly("Score rises");
        assertThat(description.dataInsights()).containsExactly("Peak: 8");
    }

    @Test
    void skipsDisabledUnsupportedAndInvalidImagesWithoutHttpRequests() {
        properties.getKnowledge().setImageDescriptionEnabled(false);
        assertThat(client().describe("chart.png", "cG5nLWRhdGE=")).isEmpty();
        properties.getKnowledge().setImageDescriptionEnabled(true);
        assertThat(client().describe("chart.svg", "cG5nLWRhdGE=")).isEmpty();
        assertThat(client().describe("chart.png", "invalid base64!")).isEmpty();
        assertThat(client().describe("chart.png", "")).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void skipsHttpFailureMalformedResponseAndMissingSummary() {
        status = 502;
        assertThat(client().describe("chart.png", "cG5nLWRhdGE=")).isEmpty();
        status = 200;
        response = "not json";
        assertThat(client().describe("chart.png", "cG5nLWRhdGE=")).isEmpty();
        response = "{\"success\":true,\"description\":{\"summary\":\" \"}}";
        assertThat(client().describe("chart.png", "cG5nLWRhdGE=")).isEmpty();
    }

    @Test
    void returnsEmptyOnTimeout() {
        ImageDescriptionClient slowClient = new ImageDescriptionClient(
                properties, WebClient.builder().exchangeFunction(request -> Mono.never()));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThat(slowClient.describe("chart.png", "cG5nLWRhdGE=")).isEmpty());
    }

    private ImageDescriptionClient client() {
        return new ImageDescriptionClient(properties, WebClient.builder());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
