package com.mindbridge.agent.config;

import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.SpringAiChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 大模型客户端装配配置。
 *
 * <p>根据 application.yml 或环境变量选择 DeepSeek 或 OpenAI 兼容客户端，
 * 让业务服务只依赖统一的 {@link AiClient} 接口。</p>
 */
@Configuration
public class AiClientConfig {

    @Bean
    public AiClient aiClient(MindBridgeProperties properties) {
        String provider = properties.getAi().getProvider().toLowerCase(java.util.Locale.ROOT);
        MindBridgeProperties.ChatApi chatApi;
        String keyName;
        if ("deepseek".equals(provider)) {
            chatApi = properties.getAi().getDeepseek();
            keyName = "DEEPSEEK_API_KEY";
        } else if ("openai".equals(provider)) {
            chatApi = properties.getAi().getOpenai();
            keyName = "OPENAI_API_KEY";
        } else {
            throw new IllegalArgumentException(
                    "Unsupported AI_PROVIDER=" + provider + ". Supported providers: deepseek, openai.");
        }
        if (chatApi.getApiKey() == null || chatApi.getApiKey().isBlank()) {
            throw new IllegalStateException("AI_PROVIDER=" + provider + " requires " + keyName + ".");
        }
        OpenAiChatModel model = openAiChatModel(properties, chatApi, "deepseek".equals(provider));
        return new SpringAiChatClient(model, model);
    }

    private OpenAiChatModel openAiChatModel(
            MindBridgeProperties properties, MindBridgeProperties.ChatApi chatApi, boolean deepseek) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(chatApi.getBaseUrl())
                .apiKey(chatApi.getApiKey())
                .build();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(chatApi.getModel())
                .temperature(properties.getAi().getTemperature())
                .maxTokens(properties.getAi().getMaxTokens());
        if (deepseek) {
            // 默认关闭思考模式，避免 512 token 上限在推理阶段耗尽而不返回正文。
            options.reasoningEffort("none");
        }
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options.build())
                .build();
    }
}
