package com.heima.codereview.start.eval;

import com.heima.codereview.core.agent.FlowAgent;
import com.heima.codereview.core.agent.conversational.ConversationContext;
import com.heima.codereview.core.agent.conversational.ReactStreamListener;
import com.heima.codereview.core.agent.conversational.SpecialistReport;
import com.heima.codereview.core.agent.planning.IntentAnalysisResult;
import com.heima.codereview.core.agent.planning.IntentType;
import com.heima.codereview.core.agent.react.ReactResult;
import com.heima.codereview.core.agent.react.ThinkingStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile("eval")
public class ToolCallEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ToolCallEvaluator.class);

    private final FlowAgent flowAgent;

    private static final Map<String, IntentType> AGENT_INTENT_MAP = Map.of(
            "security-specialist", IntentType.SECURITY_ANALYSIS,
            "review-specialist", IntentType.CODE_REVIEW,
            "performance-specialist", IntentType.PERFORMANCE_ANALYSIS,
            "architecture-specialist", IntentType.ARCHITECTURE_ANALYSIS,
            "rag-specialist", IntentType.KNOWLEDGE_RETRIEVAL,
            "local-code-specialist", IntentType.GENERAL_CODING,
            "general-coding", IntentType.GENERAL_CODING,
            "documentation-specialist", IntentType.DOCUMENTATION_GENERATION
    );

    public ToolCallEvaluator(FlowAgent flowAgent) {
        this.flowAgent = flowAgent;
    }

    public record ToolCallEvalResult(
            String testId,
            boolean intentMatch,
            boolean toolNameMatch,
            boolean paramsMatch,
            boolean forbiddenNotCalled,
            String actualIntent,
            String actualToolsCalled,
            Map<String, Object> actualParams,
            String reasoning
    ) {}

    public record ToolCallEvalSummary(
            double intentAccuracy,
            double selectionAccuracy,
            double paramsAccuracy,
            double forbiddenRate,
            List<ToolCallEvalResult> details
    ) {
        public String toPrettyString() {
            return String.format(
                    "ToolCall Summary: intent=%.1f%% tool=%.1f%% params=%.1f%% forbidden=%.1f%% total=%d",
                    intentAccuracy * 100, selectionAccuracy * 100, paramsAccuracy * 100,
                    forbiddenRate * 100, details.size()
            );
        }
    }

    public ToolCallEvalSummary evaluateBatch(List<ToolCallTestCase> cases) {
        List<ToolCallEvalResult> results = new ArrayList<>();
        for (ToolCallTestCase tc : cases) {
            results.add(evaluateByFullPipeline(tc));
        }

        long total = Math.max(1, results.size());
        long intentCorrect = results.stream().filter(ToolCallEvalResult::intentMatch).count();
        long toolCorrect = results.stream().filter(ToolCallEvalResult::toolNameMatch).count();
        long paramsCorrect = results.stream().filter(ToolCallEvalResult::paramsMatch).count();
        long forbiddenOk = results.stream().filter(ToolCallEvalResult::forbiddenNotCalled).count();

        return new ToolCallEvalSummary(
                (double) intentCorrect / total,
                (double) toolCorrect / total,
                (double) paramsCorrect / total,
                (double) forbiddenOk / total,
                results
        );
    }

    ToolCallEvalResult evaluateByFullPipeline(ToolCallTestCase tc) {
        try {
            ConversationContext ctx = buildTestContext(tc);

            IntentAnalysisResult intent = flowAgent.analyzeIntent(tc.userQuery(), ctx);
            IntentType expectedIntentType = AGENT_INTENT_MAP.getOrDefault(tc.expectedIntent(), IntentType.GENERAL_CODING);
            boolean intentMatch = intent.primaryIntent() == expectedIntentType
                    || intent.candidateIntents().contains(expectedIntentType);

            ReactResult result = flowAgent.executeConversation(tc.userQuery(), ctx, NoopListener.INSTANCE);

            Set<String> calledTools = result.steps().stream()
                    .filter(s -> "TOOL_CALL".equals(s.type()))
                    .map(ThinkingStep::toolName)
                    .collect(Collectors.toSet());

            List<String> expectedToolNames = tc.expectedTools().stream()
                    .map(ExpectedToolCall::toolName)
                    .toList();
            boolean anyExpectedCalled = expectedToolNames.stream().anyMatch(calledTools::contains);
            boolean forbiddenOk = tc.forbiddenTools().stream().noneMatch(calledTools::contains);

            String actualTools = calledTools.isEmpty() ? "FINISH(direct)" : String.join(",", calledTools);
            String actualIntent = intent.primaryIntent().name();
            String reasoning = String.format("intent=%s(expect=%s) agents=%s",
                    actualIntent, expectedIntentType.name(),
                    result.reports().stream().map(SpecialistReport::agentId).collect(Collectors.joining(",")));

            return new ToolCallEvalResult(
                    tc.id(), intentMatch, anyExpectedCalled, anyExpectedCalled, forbiddenOk,
                    actualIntent, actualTools, Map.of(), reasoning);
        } catch (Exception e) {
            log.warn("Full pipeline tool evaluation failed for {}: {}", tc.id(), e.getMessage());
            return new ToolCallEvalResult(
                    tc.id(), false, false, false, true, "ERROR", "", Map.of(), "error:" + e.getMessage());
        }
    }

    private ConversationContext buildTestContext(ToolCallTestCase tc) {
        return new ConversationContext(
                "eval-session-" + tc.id(),
                "demo-project",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static class NoopListener implements ReactStreamListener {
        static final NoopListener INSTANCE = new NoopListener();
    }
}
