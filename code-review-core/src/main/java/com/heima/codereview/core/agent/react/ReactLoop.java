package com.heima.codereview.core.agent.react;

import com.heima.codereview.core.agent.conversational.ConversationContext;
import com.heima.codereview.core.agent.conversational.ReactStreamListener;
import com.heima.codereview.core.agent.conversational.SpecialistExecutionResult;
import com.heima.codereview.core.agent.conversational.SpecialistReport;
import com.heima.codereview.core.agent.loop.AgentLoop;
import com.heima.codereview.core.agent.loop.AgentLoop.LoopRequest;
import com.heima.codereview.core.agent.specialized.SpecializedAgent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReactLoop implements AgentLoop {

    private static final int MAX_ITERATIONS = 5;
    // 仓库审查场景下的本地相关工具集合：local_file_* 直接读写审查服务器本地磁盘，
    // file_operation 把 repoUrl 当本地路径解析（见 FileOperationTool.resolveBasePath），
    // 同样不访问被审查的远程仓库。git_diff_fetch 拿到差异后，这些工具都应被跳过。
    private static final List<String> LOCAL_FILE_TOOLS = List.of(
            "local_file_list",
            "local_file_read",
            "local_file_write",
            "local_file_search",
            "local_file_delete",
            "file_operation"
    );

    @Override
    public String loopType() {
        return "REACT";
    }

    @Override
    public SpecialistExecutionResult execute(LoopRequest request) {
        return execute(request.agent(), request.taskPrompt(), request.context(), request.listener());
    }

    public SpecialistExecutionResult execute(SpecializedAgent agent,
                                             String userMessage,
                                             ConversationContext context,
                                             ReactStreamListener listener) {
        ReactStreamListener safeListener = listener == null ? new ReactStreamListener() {
        } : listener;
        List<ThinkingStep> steps = new ArrayList<>();
        List<ToolCallResult> toolResults = new ArrayList<>();
        ReactContext reactContext = new ReactContext(userMessage, context);
        ReactState state = ReactState.initial();
        String finalContent = "";
        boolean completed = false;

        while (state.iteration() < MAX_ITERATIONS) {
            int currentRound = state.iteration() + 1;
            ReactDecision decision = agent.decideNextAction(userMessage, reactContext, state);
            ThinkingStep thought = ThinkingStep.thinking(
                    agent.getId(),
                    agent.getName(),
                    "Round " + currentRound + "/" + MAX_ITERATIONS + ": "
                            + safe(decision.thought(), "collect more evidence and decide the next action")
            );
            steps.add(thought);
            safeListener.onStep(thought);

            if (decision.action() != ReactDecision.Action.TOOL || decision.toolName().isBlank()) {
                finalContent = resolveFinalContent(agent, userMessage, reactContext, toolResults, decision.finalAnswer(), safeListener);
                state = new ReactState(
                        steps,
                        toolResults,
                        currentRound,
                        state.consecutiveNoProgress(),
                        finalContent,
                        true,
                        safe(decision.terminationReason(), "decision_finish")
                );
                completed = true;
                break;
            }

            if (shouldSkipToolDueToExistingData(decision, toolResults)) {
                ThinkingStep skipThought = ThinkingStep.thinking(
                        agent.getId(),
                        agent.getName(),
                        "Skipping " + decision.toolName() + " - relevant data already retrieved. Using existing results to analyze."
                );
                steps.add(skipThought);
                safeListener.onStep(skipThought);

                String analysis = agent.generateAnalysis(userMessage, reactContext, toolResults, safeListener);
                finalContent = analysis != null ? analysis : "";
                state = new ReactState(
                        steps,
                        toolResults,
                        currentRound,
                        state.consecutiveNoProgress(),
                        finalContent,
                        true,
                        "existing_data_sufficient"
                );
                completed = true;
                break;
            }

            ToolCallResult toolResult = agent.callTool(decision.toolName(), decision.toolParams(), userMessage, reactContext, state);
            toolResults.add(toolResult);

            ThinkingStep toolStep = ThinkingStep.toolCall(
                    agent.getId(),
                    agent.getName(),
                    toolResult.toolName(),
                    toolResult.input(),
                    toolResult.output()
            );
            steps.add(toolStep);
            safeListener.onStep(toolStep);

            String observationText = agent.observeToolResult(toolResult, reactContext, state);
            ThinkingStep observation = ThinkingStep.observation(agent.getId(), agent.getName(), observationText);
            steps.add(observation);
            safeListener.onStep(observation);

            int consecutiveNoProgress = isLowValueObservation(toolResult)
                    || state.hasToolCall(toolResult.toolName(), toolResult.input())
                    || state.toolCallCount(toolResult.toolName()) >= 2
                    ? state.consecutiveNoProgress() + 1
                    : 0;

            state = new ReactState(
                    steps,
                    toolResults,
                    currentRound,
                    consecutiveNoProgress,
                    observationText,
                    false,
                    ""
            );

            if (agent.shouldTerminate(state)) {
                finalContent = resolveFinalContent(agent, userMessage, reactContext, toolResults, "", safeListener);
                state = new ReactState(
                        steps,
                        toolResults,
                        currentRound,
                        consecutiveNoProgress,
                        finalContent,
                        true,
                        state.isStuck() ? "stuck" : "enough_evidence"
                );
                completed = true;
                break;
            }
        }

        if (!completed) {
            finalContent = resolveFinalContent(agent, userMessage, reactContext, toolResults, "", safeListener);
            state = new ReactState(
                    steps,
                    toolResults,
                    Math.max(state.iteration(), MAX_ITERATIONS),
                    state.consecutiveNoProgress(),
                    finalContent,
                    true,
                    "max_iterations"
            );
        }

        if (steps.isEmpty()
                || !"OBSERVATION".equals(steps.get(steps.size() - 1).type())
                || !finalContent.equals(steps.get(steps.size() - 1).content())) {
            ThinkingStep finalObservation = ThinkingStep.observation(agent.getId(), agent.getName(), finalContent);
            steps.add(finalObservation);
            safeListener.onStep(finalObservation);
        }

        return new SpecialistExecutionResult(steps, new SpecialistReport(agent.getId(), agent.getName(), finalContent));
    }

    private String resolveFinalContent(SpecializedAgent agent,
                                       String userMessage,
                                       ReactContext reactContext,
                                       List<ToolCallResult> toolResults,
                                       String plannedAnswer,
                                       ReactStreamListener listener) {
        if (plannedAnswer != null && !plannedAnswer.isBlank()) {
            return plannedAnswer.trim();
        }
        return agent.generateAnalysis(userMessage, reactContext, toolResults, listener);
    }

    private boolean isLowValueObservation(ToolCallResult toolResult) {
        String output = toolResult == null || toolResult.output() == null ? "" : toolResult.output().trim().toLowerCase();
        return output.isBlank()
                || output.contains("not found")
                || output.contains("failed")
                || output.contains("unavailable");
    }

    private boolean shouldSkipToolDueToExistingData(ReactDecision decision, List<ToolCallResult> toolResults) {
        if (decision == null || decision.toolName() == null || toolResults == null || toolResults.isEmpty()) {
            return false;
        }
        String toolName = decision.toolName();

        if ("git_diff_fetch".equals(toolName)) {
            for (ToolCallResult result : toolResults) {
                if ("git_diff_fetch".equals(result.toolName())
                        && result.output() != null
                        && result.output().length() > 100) {
                    return true;
                }
            }
        }

        if ("file_operation".equals(toolName) || "code_search".equals(toolName) || LOCAL_FILE_TOOLS.contains(toolName)) {
            for (ToolCallResult result : toolResults) {
                if (("git_diff_fetch".equals(result.toolName()))
                        && result.output() != null
                        && result.output().length() > 100) {
                    return true;
                }
            }
        }

        return false;
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
