package com.mindbridge.agent.service.knowledge.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.PsychologicalAssessmentService;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class RagEvaluationService {

    private final KnowledgeService knowledgeService;
    private final MindBridgeProperties properties;
    private final AiClient aiClient;
    private final IntentClassifier intentClassifier;
    private final PsychologicalAssessmentService assessmentService;
    private final ObjectMapper objectMapper;
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    public RagEvaluationService(
            KnowledgeService knowledgeService,
            MindBridgeProperties properties,
            AiClient aiClient,
            IntentClassifier intentClassifier,
            PsychologicalAssessmentService assessmentService,
            ObjectMapper objectMapper
    ) {
        this.knowledgeService = knowledgeService;
        this.properties = properties;
        this.aiClient = aiClient;
        this.intentClassifier = intentClassifier;
        this.assessmentService = assessmentService;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public RagEvalReport evaluate(String datasetLocation, int topK) {
        List<RagEvalCase> cases = loadDataset(datasetLocation);
        List<RagEndToEndCaseResult> results = cases.stream()
                .map(testCase -> buildRagasCase(testCase, topK))
                .toList();
        long passedCases = results.stream()
                .filter(RagEndToEndCaseResult::passed)
                .count();
        return new RagEvalReport(
                Instant.now(),
                datasetLocation,
                properties.getKnowledge().getFusionStrategy().name(),
                topK,
                results.size(),
                passedCases,
                results.size() - passedCases,
                results);
    }

    public void writeReport(RagEvalReport report, String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(outputPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            objectMapper.writeValue(path.toFile(), report);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to write RAGAS input report: " + outputPath, exception);
        }
    }

    public String formatSummary(RagEvalReport report) {
        long casesWithContexts = report.cases().stream()
                .filter(testCase -> !testCase.retrievedContexts().isEmpty())
                .count();
        return """
                RAGAS input report completed.
                dataset=%s
                fusionStrategy=%s
                cases=%d
                passed=%d
                failed=%d
                topK=%d
                casesWithRetrievedContexts=%d
                output contains Java harness assertions; run eval/run-ragas-eval.py for optional RAGAS scores.
                """.formatted(
                report.dataset(),
                report.fusionStrategy(),
                report.totalCases(),
                report.passedCases(),
                report.failedCases(),
                report.topK(),
                casesWithContexts);
    }

    private RagEndToEndCaseResult buildRagasCase(RagEvalCase testCase, int topK) {
        List<SearchResult> retrieved = knowledgeService.retrieve(testCase.question(), topK);
        List<String> retrievedContexts = retrieved.stream()
                .map(SearchResult::content)
                .toList();
        List<String> retrievedSources = retrieved.stream()
                .map(SearchResult::source)
                .distinct()
                .toList();
        String actualIntent = actualIntent(testCase.question());
        String actualRiskLevel = actualRiskLevel(testCase.question(), actualIntent);
        String answer = generateAnswer(testCase.question(), retrievedContexts);
        List<String> failures = evaluateAssertions(
                testCase,
                actualIntent,
                actualRiskLevel,
                retrievedSources,
                retrievedContexts,
                answer);
        return new RagEndToEndCaseResult(
                testCase.id(),
                testCase.question(),
                normalizeLabel(testCase.expectedIntent()),
                actualIntent,
                normalizeLabel(testCase.expectedRiskLevel()),
                actualRiskLevel,
                retrievedSources,
                retrievedContexts,
                safeString(testCase.referenceAnswer()),
                answer,
                failures.isEmpty(),
                failures);
    }

    private String generateAnswer(String question, List<String> retrievedContexts) {
        String context = retrievedContexts.isEmpty()
                ? "无可用检索上下文。"
                : String.join("\n\n---\n\n", retrievedContexts);
        return aiClient.complete(List.of(
                AiMessage.system("""
                        你是 MindBridge 的 RAG 回答生成器，用于 RAGAS 评测样本生成。
                        请依据检索上下文回答学生问题，语气温和、具体、克制。
                        如果上下文不足，只给出安全的一般支持建议，不要编造校园流程。
                        禁止诊断疾病、开药、透露后台风险等级、Excel、MCP 或报告流程。
                        用中文回答，不超过 180 字。
                        """),
                AiMessage.user("""
                        学生问题：
                        %s

                        检索上下文：
                        %s
                        """.formatted(question, context))
        )).trim();
    }

    private List<RagEvalCase> loadDataset(String datasetLocation) {
        try {
            Resource resource = resourceLoader.getResource(datasetLocation);
            try (InputStream inputStream = resource.getInputStream()) {
                return objectMapper.readValue(inputStream, new TypeReference<>() {
                });
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to load RAG evaluation dataset: " + datasetLocation, exception);
        }
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String actualIntent(String question) {
        try {
            return intentClassifier.classify(question).name();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String actualRiskLevel(String question, String actualIntent) {
        if (IntentType.CHAT.name().equals(actualIntent)) {
            return RiskLevel.LOW.name();
        }
        try {
            return assessmentService.assess(question).risk().name();
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> evaluateAssertions(
            RagEvalCase testCase,
            String actualIntent,
            String actualRiskLevel,
            List<String> retrievedSources,
            List<String> retrievedContexts,
            String answer
    ) {
        List<String> failures = new ArrayList<>();
        expectEqual(failures, "intent", normalizeLabel(testCase.expectedIntent()), actualIntent);
        expectEqual(failures, "riskLevel", normalizeLabel(testCase.expectedRiskLevel()), actualRiskLevel);
        requireSources(failures, testCase.expectedSources(), retrievedSources);
        requireTerms(failures, "retrievedContext", testCase.expectedTerms(), joined(retrievedContexts));
        requireTerms(failures, "requiredAnswer", testCase.requiredAnswerTerms(), answer);
        requireTerms(failures, "requiredHelp", testCase.requiredHelpTerms(), answer);
        requireGroundedTerms(
                failures,
                testCase.groundedAnswerTerms(),
                testCase.minGroundedAnswerTerms(),
                answer + "\n" + joined(retrievedContexts));
        forbidAnswerTerms(failures, testCase.forbiddenAnswerTerms(), answer);
        return failures;
    }

    private void expectEqual(List<String> failures, String field, String expected, String actual) {
        if (expected.isBlank()) {
            return;
        }
        if (!expected.equals(normalizeLabel(actual))) {
            failures.add("%s expected=%s actual=%s".formatted(field, expected, safeString(actual)));
        }
    }

    private void requireSources(List<String> failures, List<String> expectedSources, List<String> actualSources) {
        for (String expected : safeList(expectedSources)) {
            boolean matched = actualSources.stream()
                    .anyMatch(source -> containsNormalized(source, expected));
            if (!matched) {
                failures.add("missing expected source: " + expected);
            }
        }
    }

    private void requireTerms(List<String> failures, String scope, List<String> terms, String value) {
        for (String term : safeList(terms)) {
            if (!containsNormalized(value, term)) {
                failures.add("missing %s term: %s".formatted(scope, term));
            }
        }
    }

    private void requireGroundedTerms(List<String> failures, List<String> terms, int minimum, String value) {
        List<String> safeTerms = safeList(terms);
        if (safeTerms.isEmpty() || minimum <= 0) {
            return;
        }
        long matched = safeTerms.stream()
                .filter(term -> containsNormalized(value, term))
                .count();
        if (matched < minimum) {
            failures.add("grounded terms matched=%d required=%d".formatted(matched, minimum));
        }
    }

    private void forbidAnswerTerms(List<String> failures, List<String> forbiddenTerms, String answer) {
        for (String term : safeList(forbiddenTerms)) {
            if (containsNormalized(answer, term)) {
                failures.add("forbidden answer term present: " + term);
            }
        }
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String joined(List<String> values) {
        return String.join("\n", values == null ? List.of() : values);
    }

    private boolean containsNormalized(String value, String expected) {
        if (value == null || expected == null || expected.isBlank()) {
            return false;
        }
        return value.toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT));
    }
}
