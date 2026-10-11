package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.ChatSession;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.service.PsychologyAssessment;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 一轮对话的 Agent 工作上下文。
 *
 * <p>Agent loop 中的每个专家 Agent 只更新自己负责的字段，下一步 Agent 根据这些状态继续执行。</p>
 */
public class AgentContext {

    private final UserAccount user;
    private final ChatSession session;
    private final String originalInput;
    private final String modelInput;
    private final List<AgentStep> steps = new ArrayList<>();

    private List<AiMessage> previousHistory = List.of();
    private List<AiMessage> modelHistory = List.of();
    private List<AiMessage> responseMessages = List.of();
    private List<SearchResult> retrievedKnowledge = List.of();
    private String memoryBrief = "无相关历史记忆。";
    private String knowledgeQuery;
    private String responsePlan = "自然回答当前问题。";
    private IntentType intent;
    private PsychologyAssessment assessment;
    private RiskLevel riskLevel = RiskLevel.LOW;
    private AgentName responseAgent = AgentName.COMPANION_AGENT;
    private boolean memoryLoaded;
    private boolean intentRouted;
    private boolean knowledgeHandled;
    private boolean knowledgeSufficient;
    private boolean riskAssessed;
    private boolean responsePlanned;
    private boolean finished;

    public AgentContext(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        this.user = user;
        this.session = session;
        this.originalInput = originalInput;
        this.modelInput = modelInput;
    }

    public UserAccount user() {
        return user;
    }

    public ChatSession session() {
        return session;
    }

    public String originalInput() {
        return originalInput;
    }

    public String modelInput() {
        return modelInput;
    }

    public List<AgentStep> steps() {
        return List.copyOf(steps);
    }

    public void addStep(AgentStep step) {
        steps.add(step);
    }

    public List<AiMessage> previousHistory() {
        return previousHistory;
    }

    public void setPreviousHistory(List<AiMessage> previousHistory) {
        this.previousHistory = List.copyOf(previousHistory);
    }

    public List<AiMessage> modelHistory() {
        return modelHistory;
    }

    public void setModelHistory(List<AiMessage> modelHistory) {
        this.modelHistory = List.copyOf(modelHistory);
    }

    public List<AiMessage> responseMessages() {
        return responseMessages;
    }

    public void setResponseMessages(List<AiMessage> responseMessages) {
        this.responseMessages = List.copyOf(responseMessages);
    }

    public List<SearchResult> retrievedKnowledge() {
        return retrievedKnowledge;
    }

    public void setRetrievedKnowledge(List<SearchResult> retrievedKnowledge) {
        this.retrievedKnowledge = List.copyOf(retrievedKnowledge);
    }

    public String memoryBrief() {
        return memoryBrief;
    }

    public void setMemoryBrief(String memoryBrief) {
        this.memoryBrief = memoryBrief;
    }

    public String knowledgeQuery() {
        return knowledgeQuery;
    }

    public void setKnowledgeQuery(String knowledgeQuery) {
        this.knowledgeQuery = knowledgeQuery;
    }

    public String responsePlan() {
        return responsePlan;
    }

    public void setResponsePlan(String responsePlan) {
        this.responsePlan = responsePlan;
    }

    public IntentType intent() {
        return intent;
    }

    public void setIntent(IntentType intent) {
        this.intent = intent;
    }

    public PsychologyAssessment assessment() {
        return assessment;
    }

    public void setAssessment(PsychologyAssessment assessment) {
        this.assessment = assessment;
    }

    public RiskLevel riskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public AgentName responseAgent() {
        return responseAgent;
    }

    public void setResponseAgent(AgentName responseAgent) {
        this.responseAgent = responseAgent;
    }

    public boolean memoryLoaded() {
        return memoryLoaded;
    }

    public void markMemoryLoaded() {
        this.memoryLoaded = true;
    }

    public boolean intentRouted() {
        return intentRouted;
    }

    public void markIntentRouted() {
        this.intentRouted = true;
    }

    public boolean knowledgeHandled() {
        return knowledgeHandled;
    }

    public void markKnowledgeHandled() {
        this.knowledgeHandled = true;
    }

    public boolean knowledgeSufficient() {
        return knowledgeSufficient;
    }

    public void setKnowledgeSufficient(boolean knowledgeSufficient) {
        this.knowledgeSufficient = knowledgeSufficient;
    }

    public boolean riskAssessed() {
        return riskAssessed;
    }

    public void markRiskAssessed() {
        this.riskAssessed = true;
    }

    public boolean responsePlanned() {
        return responsePlanned;
    }

    public void markResponsePlanned() {
        this.responsePlanned = true;
    }

    public boolean finished() {
        return finished;
    }

    public void finish() {
        this.finished = true;
    }
}
