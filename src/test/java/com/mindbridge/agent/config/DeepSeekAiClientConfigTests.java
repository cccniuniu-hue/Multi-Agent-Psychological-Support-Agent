package com.mindbridge.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.controller.AgentStatusController;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.SpringAiChatClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeepSeekAiClientConfigTests {

    @Test
    void deepSeekIsTheDefaultChatProvider() {
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getAi().getDeepseek().setApiKey("test-key");

        assertThat(properties.getAi().getProvider()).isEqualTo("deepseek");
        assertThat(new AiClientConfig().aiClient(properties)).isInstanceOf(SpringAiChatClient.class);
        assertThat(new AgentStatusController(properties).status().model()).isEqualTo("deepseek-flash");
    }

    @Test
    void deepSeekRequiresItsOwnKey() {
        MindBridgeProperties properties = new MindBridgeProperties();

        assertThatThrownBy(() -> new AiClientConfig().aiClient(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void sendsDeepSeekChatRequestThroughOpenAiCompatibleEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"chat-1\",\"object\":\"chat.completion\",\"created\":1,"
                    + "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"message\":{\"role\":"
                    + "\"assistant\",\"content\":\"你好\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            MindBridgeProperties properties = new MindBridgeProperties();
            properties.getAi().getDeepseek().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getAi().getDeepseek().setApiKey("test-key");

            String answer = new AiClientConfig().aiClient(properties).complete(List.of(AiMessage.user("你好")));
            JsonNode body = new ObjectMapper().readTree(requestBody.get());

            assertThat(answer).isEqualTo("你好");
            assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
            assertThat(body.path("model").asText()).isEqualTo("deepseek-flash");
            assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");
        } finally {
            server.stop(0);
        }
    }
}
