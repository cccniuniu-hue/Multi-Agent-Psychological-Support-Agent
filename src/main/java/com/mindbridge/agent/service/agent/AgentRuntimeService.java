package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.UserAccount;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * MindBridge Agent Loop 运行时。
 *
 * <p>每轮学生输入都会进入有限步循环：读取记忆、主控路由、知识检索、风险守护和回复规划。
 * 这里不是无限自主循环，而是受步数限制的安全 agent loop，适合心理安全场景。</p>
 */
@Service
public class AgentRuntimeService {

    private static final int MAX_STEPS = 5;

    private final List<MindBridgeAgent> agents;

    public AgentRuntimeService(
            MemoryAgent memoryAgent,
            SupervisorAgent supervisorAgent,
            KnowledgeAgent knowledgeAgent,
            RiskGuardianAgent riskGuardianAgent,
            CompanionAgent companionAgent,
            CounselorAgent counselorAgent
    ) {
        // 顺序就是 Supervisor 架构下的协作优先级；每个 Agent 通过 supports 判断是否该接手。
        this.agents = List.of(
                memoryAgent,
                supervisorAgent,
                knowledgeAgent,
                riskGuardianAgent,
                companionAgent,
                counselorAgent);
    }

    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        AgentContext context = new AgentContext(user, session, originalInput, modelInput);
        for (int step = 1; step <= MAX_STEPS && !context.finished(); step++) {
            MindBridgeAgent agent = nextAgent(context);
            AgentDecision decision = agent.act(context);
            if (decision.complete()
                    && agent.name() != AgentName.COMPANION_AGENT
                    && agent.name() != AgentName.COUNSELOR_AGENT) {
                throw new IllegalStateException("Agent loop cannot finish before response planning.");
            }
            context.addStep(AgentStep.of(step, agent.name(), decision, context));
            if (decision.complete()) {
                context.finish();
            }
        }
        if (!context.finished()) {
            throw new IllegalStateException("Agent loop exceeded maximum steps: " + MAX_STEPS);
        }
        return AgentRunResult.from(context);
    }

    private MindBridgeAgent nextAgent(AgentContext context) {
        int completed = context.steps().size();
        if (completed >= 2 && (!context.intentRouted() || context.intent() == null)) {
            throw new IllegalStateException("Agent loop has no routed intent.");
        }
        MindBridgeAgent agent = switch (completed) {
            case 0 -> agents.get(0);
            case 1 -> agents.get(1);
            case 2 -> context.intent() == IntentType.CHAT
                    ? agents.get(4) : agents.get(2);
            case 3 -> {
                if (context.intent() == IntentType.CHAT) {
                    throw new IllegalStateException("Chat response agent did not finish.");
                }
                yield agents.get(3);
            }
            case 4 -> agents.get(5);
            default -> throw new IllegalStateException("Agent loop exceeded its route.");
        };
        if (!agent.supports(context)) {
            throw new IllegalStateException("Agent loop state does not allow " + agent.name());
        }
        return agent;
    }
}
