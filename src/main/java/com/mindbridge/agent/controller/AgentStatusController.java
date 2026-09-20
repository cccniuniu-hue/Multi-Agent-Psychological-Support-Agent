package com.mindbridge.agent.controller;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
/**
 * 智能体运行状态接口。
 *
 * <p>前端用它展示当前 provider、项目模型名称、RAG 参数和模型连接模式。</p>
 */
public class AgentStatusController {

    private final MindBridgeProperties properties;

    public AgentStatusController(MindBridgeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public AgentStatusResponse status() {
        // realModelEnabled 只表示当前使用真实模型客户端，不代表业务评估一定会展示给学生。
        String provider = properties.getAi().getProvider().toLowerCase(Locale.ROOT);
        boolean realModelEnabled = "deepseek".equals(provider) || "openai".equals(provider);
        return new AgentStatusResponse(
                provider,
                modelName(provider),
                realModelEnabled,
                properties.getKnowledge().isUseChroma(),
                properties.getKnowledge().getTopK(),
                properties.getKnowledge().isRerankerEnabled(),
                properties.getKnowledge().getRerankerCandidateLimit(),
                properties.getMemory().isUseChroma(),
                properties.getMemory().getTopK(),
                realModelEnabled ? "正在使用真实大模型客户端。" : "当前模型 provider 配置不受支持。"
        );
    }

    private String modelName(String provider) {
        if ("deepseek".equals(provider)) {
            return properties.getAi().getDeepseek().getModel();
        }
        if ("openai".equals(provider)) {
            return properties.getAi().getOpenai().getModel();
        }
        return "unknown";
    }

    /**
     * 前端状态栏需要的最小状态信息。
     */
    public record AgentStatusResponse(
            String provider,
            String model,
            boolean realModelEnabled,
            boolean chromaEnabled,
            int ragTopK,
            boolean rerankerEnabled,
            int rerankerCandidateLimit,
            boolean memoryChromaEnabled,
            int memoryTopK,
            String note
    ) {
    }
}
