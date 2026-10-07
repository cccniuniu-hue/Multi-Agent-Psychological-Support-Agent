package com.mindbridge.agent.service.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.UserAccount;
import org.junit.jupiter.api.Test;

class AgentRuntimeRoutingTests {

    @Test
    void cannotSkipRiskGuardianWhenKnowledgeMarksRiskAssessed() {
        Fixture fixture = new Fixture(false);

        assertThatThrownBy(fixture::run).isInstanceOf(IllegalStateException.class);
        verify(fixture.riskGuardianAgent).supports(any());
        verify(fixture.counselorAgent, never()).act(any());
    }

    @Test
    void cannotFinishBeforeRiskGuardian() {
        Fixture fixture = new Fixture(true);

        assertThatThrownBy(fixture::run).isInstanceOf(IllegalStateException.class);
        verify(fixture.counselorAgent, never()).act(any());
    }

    private static class Fixture {
        private final MemoryAgent memoryAgent = mock(MemoryAgent.class);
        private final SupervisorAgent supervisorAgent = mock(SupervisorAgent.class);
        private final KnowledgeAgent knowledgeAgent = mock(KnowledgeAgent.class);
        private final RiskGuardianAgent riskGuardianAgent = mock(RiskGuardianAgent.class);
        private final CompanionAgent companionAgent = mock(CompanionAgent.class);
        private final CounselorAgent counselorAgent = mock(CounselorAgent.class);
        private final AgentRuntimeService runtime;

        private Fixture(boolean finishAtKnowledge) {
            when(memoryAgent.supports(any())).thenReturn(true);
            when(memoryAgent.act(any())).thenAnswer(invocation -> {
                AgentContext context = invocation.getArgument(0);
                context.markMemoryLoaded();
                return AgentDecision.continueWith(AgentAction.READ_MEMORY, "memory");
            });
            when(supervisorAgent.supports(any())).thenReturn(true);
            when(supervisorAgent.act(any())).thenAnswer(invocation -> {
                AgentContext context = invocation.getArgument(0);
                context.setIntent(IntentType.RISK);
                context.markIntentRouted();
                return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "risk");
            });
            when(knowledgeAgent.supports(any())).thenReturn(true);
            when(knowledgeAgent.act(any())).thenAnswer(invocation -> {
                AgentContext context = invocation.getArgument(0);
                context.markKnowledgeHandled();
                context.markRiskAssessed();
                return finishAtKnowledge
                        ? AgentDecision.finish(AgentAction.RETRIEVE_KNOWLEDGE, "early finish")
                        : AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE, "knowledge");
            });
            when(counselorAgent.supports(any())).thenReturn(true);
            when(counselorAgent.act(any())).thenReturn(AgentDecision.finish(AgentAction.PLAN_RESPONSE, "counselor"));
            runtime = new AgentRuntimeService(memoryAgent, supervisorAgent, knowledgeAgent,
                    riskGuardianAgent, companionAgent, counselorAgent);
        }

        private void run() {
            runtime.run(new UserAccount(), new ChatSession(), "help", "help");
        }
    }
}
