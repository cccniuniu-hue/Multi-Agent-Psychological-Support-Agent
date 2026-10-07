package com.mindbridge.agent.service.agent;

import java.time.Instant;

/**
 * Agent loop 的一步执行轨迹。
 *
 * <p>动作摘要和路由状态会随本轮运行结果持久化为管理员 trace。</p>
 */
public record AgentStep(
        int step,
        AgentName agent,
        AgentAction action,
        String observation,
        Instant createdAt
) {
    public static AgentStep of(int step, AgentName agent, AgentDecision decision, AgentContext context) {
        String observation = "%s; state[intent=%s, memoryLoaded=%s, intentRouted=%s, knowledgeHandled=%s, "
                + "riskAssessed=%s, responsePlanned=%s]";
        return new AgentStep(step, agent, decision.action(), observation.formatted(
                decision.observation() == null ? "" : decision.observation(),
                context.intent(),
                context.memoryLoaded(),
                context.intentRouted(),
                context.knowledgeHandled(),
                context.riskAssessed(),
                context.responsePlanned()), Instant.now());
    }
}
