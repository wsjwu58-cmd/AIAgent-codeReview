package com.heima.codereview.core.agent;

import com.heima.codereview.common.utils.IdUtils;
import com.heima.codereview.core.agent.aggregation.FlowResultAggregator;
import com.heima.codereview.core.agent.conversational.ConversationContext;
import com.heima.codereview.core.agent.conversational.ReactStreamListener;
import com.heima.codereview.core.agent.conversational.SimpleAgent;
import com.heima.codereview.core.agent.conversational.SpecialistExecutionResult;
import com.heima.codereview.core.agent.conversational.SpecialistReport;
import com.heima.codereview.core.agent.execution.ParallelExecutor;
import com.heima.codereview.core.agent.loop.AgentLoop;
import com.heima.codereview.core.agent.planning.BacktrackingPlanner;
import com.heima.codereview.core.agent.planning.ExecutionHistory;
import com.heima.codereview.core.agent.planning.ExecutionStrategy;
import com.heima.codereview.core.agent.planning.IntentAnalysisResult;
import com.heima.codereview.core.agent.planning.IntentTaskPlanner;
import com.heima.codereview.core.agent.planning.IntentType;
import com.heima.codereview.core.agent.planning.PlannedTask;
import com.heima.codereview.core.agent.planning.PlannedTasks;
import com.heima.codereview.core.agent.planning.ReflectionResult;
import com.heima.codereview.core.agent.planning.SubTask;
import com.heima.codereview.core.agent.planning.TaskExecutionResult;
import com.heima.codereview.core.agent.react.ReactResult;
import com.heima.codereview.core.agent.react.ThinkingStep;
import com.heima.codereview.core.agent.specialized.ReviewSpecialistAgent;
import com.heima.codereview.core.agent.specialized.ReviewSpecialistOutcome;
import com.heima.codereview.core.chain.ReviewContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class FlowAgent extends BaseAgent {

    private static final int MAX_PLANNED_TASKS = 6;
    private static final int MAX_TASK_DEPTH = 3;
    private static final int PARALLEL_TIMEOUT_SECONDS = 120;
    // 超过此阈值则使用LLM进行任务规划
    private static final int COMPLEXITY_THRESHOLD = 40;

    private final ReviewSpecialistAgent reviewSpecialistAgent;
    private final SimpleAgent simpleAgent;
    private final AgentLoop agentLoop;
    private final IntentTaskPlanner intentTaskPlanner;
    private final FlowResultAggregator resultAggregator;
    private final Map<String, SpecialistAgent> specialistRegistry;
    private final ParallelExecutor parallelExecutor;

    public FlowAgent(ReviewSpecialistAgent reviewSpecialistAgent,
                     List<SpecialistAgent> specialists,
                     SimpleAgent simpleAgent,
                     AgentLoop agentLoop,
                     IntentTaskPlanner intentTaskPlanner,
                     FlowResultAggregator resultAggregator,
                     @Qualifier("specialistExecutor") ThreadPoolTaskExecutor specialistExecutor) {
        this.reviewSpecialistAgent = reviewSpecialistAgent;
        this.simpleAgent = simpleAgent;
        this.agentLoop = agentLoop;
        this.intentTaskPlanner = intentTaskPlanner;
        this.resultAggregator = resultAggregator;
        this.specialistRegistry = new LinkedHashMap<>();
        for (SpecialistAgent specialist : specialists) {
            this.specialistRegistry.put(specialist.getId(), specialist);
        }
        this.parallelExecutor = new ParallelExecutor(specialistExecutor);
    }

    @Override
    public String getName() {
        return "Planner Flow Agent";
    }

    public FlowResult execute(AgentExecutionContext context, AgentEventListener listener) {
        FlowResult result = new FlowResult();
        ReviewContext reviewContext = context.reviewContext();
        String sessionId = context.sessionId();
        ConversationContext conversationContext = toConversationContext(reviewContext);
        String userMessage = buildReviewRequest(reviewContext);
        //分析意图
        IntentAnalysisResult intent = intentTaskPlanner.analyzeIntent(userMessage, conversationContext, true);

        listener.onEvent(sessionId, "agent_start", Map.of("agentId", getId(), "name", getName()));
        listener.onEvent(sessionId, "planner_intent", Map.of(
                "intent", intent.primaryIntent().name(),
                "rationale", intent.rationale()
        ));

        //专门用来存放并行执行的那些辅助任务的结果
        List<SpecialistReport> parallelReports = new ArrayList<>();
        List<SpecialistExecutionResult> parallelResults = new ArrayList<>();
        //一个总账本，记录所有执行过的任务（包括并行的和后续串行的）
        List<TaskExecutionResult> allTaskResults = new ArrayList<>();
        //记录整个思考过程（Thinking Steps），用于最后生成思维链（Chain of Thought）展示给用户
        List<ThinkingStep> allSteps = new ArrayList<>();

        List<SubTask> initialTasks = buildInitialTasks(intent);

        // 复杂请求使用LLM规划的任务
        if (intent.complexityScore() >= COMPLEXITY_THRESHOLD) {
            PlannedTasks planned = intentTaskPlanner.planTasks(
                    userMessage, conversationContext, intent, MAX_PLANNED_TASKS);

            if (!planned.tasks().isEmpty()) {
                executePlannedTasks(planned, userMessage, conversationContext, intent, createBridgeListener(sessionId, listener),
                        parallelReports, parallelResults, allTaskResults, allSteps);
            }
        } else {
            // 简单请求使用模板方式
            List<SubTask> supplementalTasks = initialTasks.stream()
                    .filter(task -> task.intentType() != IntentType.CODE_REVIEW)
                    .toList();

            if (!supplementalTasks.isEmpty()) {
                parallelReports = runParallelSpecialists(supplementalTasks, userMessage, conversationContext, intent, createBridgeListener(sessionId, listener), parallelResults, allTaskResults, allSteps);
            }
        }

        //串行执行
        ReviewSpecialistOutcome primaryOutcome = runStructuredReview(context, listener, result, allTaskResults);

        List<SpecialistReport> reports = new ArrayList<>(parallelReports);
        if (primaryOutcome != null) {
            reports.add(0, new SpecialistReport(reviewSpecialistAgent.getId(), reviewSpecialistAgent.getName(), safe(primaryOutcome.summary())));
        }

        List<SubTask> followupTasks = new ArrayList<>();
        for (TaskExecutionResult tr : allTaskResults) {
            if (tr.success() && tr.proposedSubTasks() != null) {
                for (SubTask proposal : tr.proposedSubTasks()) {
                    if (proposal.depth() > 0) {
                        followupTasks.add(proposal);
                    }
                }
            }
        }

        ExecutionHistory history = new ExecutionHistory();
        //执行后续任务
        PlanningExecution followupExecution = runPlannerSequential(
                userMessage,
                conversationContext,
                intent,
                followupTasks,
                createBridgeListener(sessionId, listener),
                history,
                MAX_PLANNED_TASKS - 1
        );

        reports.addAll(followupExecution.reports());
        allTaskResults.addAll(followupExecution.taskResults());
        allSteps.addAll(followupExecution.steps());

        for (TaskExecutionResult taskResult : allTaskResults) {
            if (taskResult.success()) {
                result.getAgentOutputs().putIfAbsent(taskResult.agentId(), safe(taskResult.output()));
            }
        }

        String summary = resultAggregator.aggregateReviewResults(
                intent,
                conversationContext,
                result.getReport(),
                result.getAdvisorOutput(),
                result.getRefactoredCode(),
                allTaskResults,
                reports,
                primaryOutcome != null ? primaryOutcome.summary() : ""
        );
        result.setSummary(summary);
        if (result.getReport() != null && summary != null && !summary.isBlank()) {
            result.getReport().setSummary(summary);
        }
        listener.onEvent(sessionId, "agent_complete", Map.of("agentId", getId(), "name", getName(), "content", safe(summary)));
        listener.onEvent(sessionId, "done", Map.of("reviewId", reviewContext.reviewId()));
        return result;
    }

    //串行执行审查链
    private ReviewSpecialistOutcome runStructuredReview(AgentExecutionContext context,
                                                        AgentEventListener listener,
                                                        FlowResult result,
                                                        List<TaskExecutionResult> allTaskResults) {
        String sessionId = context.sessionId();
        SubTask reviewTask = SubTask.of(
                IdUtils.withPrefix("task"),
                IntentType.CODE_REVIEW,
                "Run structured code review",
                "Execute the existing review chain and produce refactoring guidance.",
                reviewSpecialistAgent.getId(),
                0,
                ""
        );
        listener.onEvent(sessionId, "agent_start", Map.of("agentId", reviewSpecialistAgent.getId(), "name", reviewSpecialistAgent.getName()));
        ReviewSpecialistOutcome primaryOutcome = reviewSpecialistAgent.review(context, listener);
        listener.onEvent(sessionId, "agent_complete", Map.of(
                "agentId", reviewSpecialistAgent.getId(),
                "name", reviewSpecialistAgent.getName(),
                "content", safe(primaryOutcome.summary())
        ));

        result.setReport(primaryOutcome.report());
        result.setAdvisorOutput(primaryOutcome.advisorOutput());
        result.setRefactoredCode(primaryOutcome.refactoredCode());
        result.getAgentOutputs().put(reviewSpecialistAgent.getId(), safe(primaryOutcome.summary()));
        result.getAgentOutputs().put("advisor-agent", safe(primaryOutcome.advisorOutput()));
        result.getAgentOutputs().put("refactor-agent", safe(primaryOutcome.refactoredCode()));

        allTaskResults.add(new TaskExecutionResult(
                reviewTask,
                reviewSpecialistAgent.getId(),
                reviewSpecialistAgent.getName(),
                true,
                safe(primaryOutcome.summary()),
                "",
                ReflectionResult.ok(),
                primaryOutcome.proposedSubTasks(),
                new SpecialistReport(reviewSpecialistAgent.getId(), reviewSpecialistAgent.getName(), safe(primaryOutcome.summary()))
        ));
        return primaryOutcome;
    }

    public ReactResult executeConversation(String userMessage,
                                           ConversationContext context,
                                           ReactStreamListener listener) {
        IntentAnalysisResult intent = intentTaskPlanner.analyzeIntent(userMessage, context, false);
        if (intent.isSimpleAnswer()) {
            String content = simpleAgent.respond(userMessage, context);
            List<ThinkingStep> steps = List.of(
                    ThinkingStep.thinking(getId(), getName(), "问题较简单，直接走快速回答路径。"),
                    ThinkingStep.observation(getId(), getName(), content)
            );
            ReactResult result = new ReactResult(steps, content, intent.rationale(), List.of());
            listener.onComplete(result);
            return result;
        }

        PlanningExecution execution = runPlannerSequential(
                userMessage,
                context,
                intent,
                decomposeTask(intent, context),
                listener,
                new ExecutionHistory(),
                MAX_PLANNED_TASKS
        );
        String finalContent = resultAggregator.aggregateReviewResults(intent, context, null, "", "", execution.taskResults(), execution.reports(), "");
        ReactResult result = new ReactResult(execution.steps(), finalContent, intent.rationale(), execution.reports());
        listener.onComplete(result);
        return result;
    }

    public IntentAnalysisResult analyzeIntent(String userMessage, ConversationContext context) {
        return intentTaskPlanner.analyzeIntent(userMessage, context, false);
    }

    public List<SubTask> decomposeTask(IntentAnalysisResult intent, ConversationContext context) {
        return intentTaskPlanner.decomposeTask(intent, context);
    }

    private List<SubTask> buildInitialTasks(IntentAnalysisResult intent) {
        return intentTaskPlanner.buildInitialTasks(intent);
    }

    /**
     * 执行LLM规划的任务
     * 根据执行策略决定并行或串行执行
     */
    private void executePlannedTasks(PlannedTasks planned,
                                     String userMessage,
                                     ConversationContext context,
                                     IntentAnalysisResult intent,
                                     ReactStreamListener listener,
                                     List<SpecialistReport> parallelReports,
                                     List<SpecialistExecutionResult> parallelResults,
                                     List<TaskExecutionResult> allTaskResults,
                                     List<ThinkingStep> allSteps) {
        List<SubTask> subTasks = convertToSubTasks(planned.tasks());

        switch (planned.strategy()) {
            case PARALLEL:
                // PARALLEL策略：全部并行执行（依赖关系由LLM规划器保证）
                if (!subTasks.isEmpty()) {
                    parallelReports = runParallelSpecialists(subTasks, userMessage, context, intent, listener, parallelResults, allTaskResults, allSteps);
                }
                break;
            case MIXED:
                // MIXED策略：根据优先级分组，高优先级并行，低优先级串行
                List<SubTask> highPriority = subTasks.stream()
                        .filter(t -> t.depth() == 0)
                        .toList();
                List<SubTask> lowPriority = subTasks.stream()
                        .filter(t -> t.depth() > 0)
                        .toList();
                if (!highPriority.isEmpty()) {
                    parallelReports = runParallelSpecialists(highPriority, userMessage, context, intent, listener, parallelResults, allTaskResults, allSteps);
                }
                if (!lowPriority.isEmpty()) {
                    PlanningExecution execution = runPlannerSequential(
                            userMessage, context, intent, lowPriority, listener, new ExecutionHistory(), MAX_PLANNED_TASKS);
                    parallelReports.addAll(execution.reports());
                    allTaskResults.addAll(execution.taskResults());
                    allSteps.addAll(execution.steps());
                }
                break;
            case SEQUENTIAL:
            default:
                // SEQUENTIAL策略：全部串行执行
                PlanningExecution execution = runPlannerSequential(
                        userMessage, context, intent, subTasks, listener, new ExecutionHistory(), MAX_PLANNED_TASKS);
                parallelReports = execution.reports();
                allTaskResults = execution.taskResults();
                allSteps = execution.steps();
                break;
        }
    }

    /** 将PlannedTask转换为SubTask */
    private List<SubTask> convertToSubTasks(List<PlannedTask> plannedTasks) {
        return plannedTasks.stream()
                .map(pt -> SubTask.of(
                        pt.id(),
                        pt.intentType(),
                        pt.title(),
                        pt.description(),
                        pt.agentId(),
                        pt.priority() - 1,  // priority 1-5 转为 depth
                        ""
                ))
                .toList();
    }

    private List<SpecialistReport> runParallelSpecialists(List<SubTask> tasks,
                                                        String userMessage,
                                                        ConversationContext context,
                                                        IntentAnalysisResult intent,
                                                        ReactStreamListener listener,
                                                        List<SpecialistExecutionResult> parallelResults,
                                                        List<TaskExecutionResult> allTaskResults,
                                                        List<ThinkingStep> allSteps) {
        List<SpecialistReport> reports = new ArrayList<>();
        if (tasks.isEmpty()) {
            return reports;
        }

        List<ParallelSpecialistTask> executableTasks = new ArrayList<>();
        //为每个任务匹配对应的agent
        for (SubTask task : tasks) {
            SpecialistAgent specialist = resolveSpecialist(task, intent);
            if (specialist == null) {
                continue;
            }
            listener.onAgentStart(specialist.getId(), specialist.getName());
            executableTasks.add(new ParallelSpecialistTask(task, specialist));
        }

        if (executableTasks.isEmpty()) {
            return reports;
        }

        //并行执行
        List<ParallelExecutor.ParallelExecutionResult<ParallelSpecialistTask, SpecialistExecutionResult>> executionResults =
                parallelExecutor.execute(
                        executableTasks,
                        item -> item.specialist().executeTask(userMessage, context, item.task(), agentLoop, listener),
                        Duration.ofSeconds(PARALLEL_TIMEOUT_SECONDS)
                );

        for (ParallelExecutor.ParallelExecutionResult<ParallelSpecialistTask, SpecialistExecutionResult> executionResult : executionResults) {
            ParallelSpecialistTask parallelTask = executionResult.task();
            SpecialistAgent specialist = parallelTask.specialist();
            SubTask task = parallelTask.task();

            if (executionResult.success()) {
                SpecialistExecutionResult execResult = executionResult.result();
                parallelResults.add(execResult);
                allSteps.addAll(execResult.steps());
                SpecialistReport report = execResult.report();
                reports.add(report);
                listener.onAgentComplete(report);

                allTaskResults.add(new TaskExecutionResult(
                        task,
                        specialist.getId(),
                        execResult.report().agentName(),
                        true,
                        safe(execResult.report().summary()),
                        "",
                        ReflectionResult.ok(),
                        List.of(),
                        report
                ));
                continue;
            }

            String reason = executionResult.timedOut()
                    ? "Parallel specialist execution timed out."
                    : safe(executionResult.error() == null ? "" : executionResult.error().getMessage());
            if (reason.isBlank()) {
                reason = "Parallel specialist execution failed.";
            }
            listener.onError(reason, executionResult.error());
            allTaskResults.add(new TaskExecutionResult(
                    task,
                    specialist.getId(),
                    specialist.getName(),
                    false,
                    "",
                    reason,
                    ReflectionResult.gap(reason, List.of()),
                    List.of(),
                    new SpecialistReport(specialist.getId(), specialist.getName(), reason)
            ));
        }

        return reports;
    }

    private PlanningExecution runPlannerSequential(String userMessage,
                                         ConversationContext context,
                                         IntentAnalysisResult intent,
                                         List<SubTask> initialTasks,
                                         ReactStreamListener listener,
                                         ExecutionHistory history,
                                         int maxTasks) {
        ArrayDeque<SubTask> queue = new ArrayDeque<>(initialTasks == null ? List.of() : initialTasks);
        Set<String> queuedTaskKeys = new LinkedHashSet<>();
        queue.forEach(task -> queuedTaskKeys.add(taskKey(task)));
        BacktrackingPlanner backtrackingPlanner = new BacktrackingPlanner();
        List<TaskExecutionResult> taskResults = new ArrayList<>();
        List<SpecialistReport> reports = new ArrayList<>();
        List<ThinkingStep> allSteps = new ArrayList<>();

        while (!queue.isEmpty() && taskResults.size() < Math.max(maxTasks, 0)) {
            SubTask task = queue.removeFirst();
            queuedTaskKeys.remove(taskKey(task));
            if (task.depth() > MAX_TASK_DEPTH) {
                continue;
            }
            if (history.isPathFailed(task) && !backtrackingPlanner.canRetry(task, history)) {
                continue;
            }

            SpecialistAgent specialist = resolveSpecialist(task, intent);
            if (specialist == null) {
                history.recordFailure(task, "planner", "No specialist can handle the task.");
                for (SubTask alternative : backtrackingPlanner.findAlternativePath(task, intent)) {
                    if (acceptProposal(alternative, queuedTaskKeys, history)) {
                        queue.addLast(alternative);
                        queuedTaskKeys.add(taskKey(alternative));
                    }
                }
                continue;
            }

            listener.onAgentStart(specialist.getId(), specialist.getName());
            try {
                SpecialistExecutionResult execution = specialist.executeTask(userMessage, context, task, agentLoop, listener);
                allSteps.addAll(execution.steps());
                SpecialistReport report = execution.report();
                reports.add(report);
                listener.onAgentComplete(report);

                ReflectionResult reflection = specialist.reflect(intent, task, report.summary(), context);
                List<SubTask> proposals = new ArrayList<>(specialist.proposeSubTasks(task, report.summary(), context));
                proposals.addAll(reflection.remediationTasks());

                taskResults.add(new TaskExecutionResult(
                        task,
                        specialist.getId(),
                        specialist.getName(),
                        true,
                        report.summary(),
                        "",
                        reflection,
                        proposals,
                        report
                ));
                history.recordSuccess(task, specialist.getId(), report.summary());
                for (SubTask proposal : proposals) {
                    if (acceptProposal(proposal, queuedTaskKeys, history)) {
                        queue.addLast(proposal);
                        queuedTaskKeys.add(taskKey(proposal));
                    }
                }
            } catch (Exception e) {
                String reason = safe(e.getMessage()).isBlank() ? "Unknown task failure" : safe(e.getMessage());
                history.recordFailure(task, specialist.getId(), reason);
                listener.onError(reason, e);
                List<SubTask> alternatives = backtrackingPlanner.findAlternativePath(task, intent);
                taskResults.add(new TaskExecutionResult(
                        task,
                        specialist.getId(),
                        specialist.getName(),
                        false,
                        "",
                        reason,
                        ReflectionResult.gap(reason, alternatives),
                        alternatives,
                        new SpecialistReport(specialist.getId(), specialist.getName(), reason)
                ));
                for (SubTask alternative : alternatives) {
                    if (acceptProposal(alternative, queuedTaskKeys, history)) {
                        queue.addLast(alternative);
                        queuedTaskKeys.add(taskKey(alternative));
                    }
                }
            }
        }
        return new PlanningExecution(taskResults, reports, allSteps);
    }

    private SpecialistAgent resolveSpecialist(SubTask task, IntentAnalysisResult intent) {
        if (task == null) {
            return null;
        }
        if (task.preferredAgentId() != null && !task.preferredAgentId().isBlank()) {
            SpecialistAgent preferred = specialistRegistry.get(task.preferredAgentId());
            if (preferred != null && preferred.canHandle(task)) {
                return preferred;
            }
        }
        return specialistRegistry.values().stream()
                .filter(agent -> agent.canHandle(task))
                .findFirst()
                .orElseGet(() -> specialistRegistry.values().stream()
                        .filter(agent -> agent.canHandle(intent))
                        .findFirst()
                        .orElse(null));
    }

    private boolean acceptProposal(SubTask proposal, Set<String> queuedTaskKeys, ExecutionHistory history) {
        if (proposal == null || proposal.depth() > MAX_TASK_DEPTH) {
            return false;
        }
        if (history.isPathFailed(proposal)) {
            return false;
        }
        if (queuedTaskKeys.contains(taskKey(proposal))) {
            return false;
        }
        return proposal.preferredAgentId().isBlank() || specialistRegistry.containsKey(proposal.preferredAgentId());
    }

    private ReactStreamListener createBridgeListener(String sessionId, AgentEventListener listener) {
        return new ReactStreamListener() {
            @Override
            public void onAgentStart(String agentId, String agentName) {
                listener.onEvent(sessionId, "agent_start", Map.of("agentId", safe(agentId), "name", safe(agentName)));
            }

            @Override
            public void onStep(ThinkingStep step) {
                if ("TOOL_CALL".equals(step.type())) {
                    listener.onEvent(sessionId, "tool_call", Map.of(
                            "agentId", safe(step.agentId()),
                            "agentName", safe(step.agentName()),
                            "tool", safe(step.toolName()),
                            "input", safe(step.input()),
                            "output", safe(step.output())
                    ));
                    return;
                }
                listener.onEvent(sessionId, "thinking", Map.of(
                        "agentId", safe(step.agentId()),
                        "agentName", safe(step.agentName()),
                        "type", safe(step.type()),
                        "content", safe(step.content())
                ));
            }

            @Override
            public void onAgentStream(String agentId, String chunk) {
                listener.onEvent(sessionId, "agent_stream", Map.of(
                        "agentId", safe(agentId),
                        "content", safe(chunk)
                ));
            }

            @Override
            public void onAgentComplete(SpecialistReport report) {
                listener.onEvent(sessionId, "agent_complete", Map.of(
                        "agentId", safe(report.agentId()),
                        "name", safe(report.agentName()),
                        "content", safe(report.summary())
                ));
            }

            @Override
            public void onError(String message, Throwable throwable) {
                listener.onEvent(sessionId, "error", Map.of("message", safe(message)));
            }
        };
    }

    private ConversationContext toConversationContext(ReviewContext context) {
        List<String> messages = context.recentMessages() == null
                ? List.of()
                : context.recentMessages().stream()
                .map(item -> item.role() + ": " + item.content())
                .toList();
        List<String> histories = context.historicalReviews() == null ? List.of() : List.copyOf(context.historicalReviews());
        return new ConversationContext(
                context.sessionId(),
                safe(context.projectId()),
                safe(context.repoUrl()),
                safe(context.branch()),
                safe(context.language()),
                safe(context.codeContent()),
                safe(context.codeContent()),
                messages,
                List.of(),
                histories,
                List.of()
        );
    }

    private String buildReviewRequest(ReviewContext context) {
        return "请对当前代码变更做结构化代码审查，语言=" + safe(context.language())
                + "，reviewId=" + safe(context.reviewId())
                + "，重点关注正确性、可维护性、安全与性能。";
    }

    private String taskKey(SubTask task) {
        return task.intentType() + ":" + safe(task.title()) + ":" + safe(task.preferredAgentId());
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private record PlanningExecution(
            List<TaskExecutionResult> taskResults,
            List<SpecialistReport> reports,
            List<ThinkingStep> steps
    ) {
    }

    private record ParallelSpecialistTask(SubTask task, SpecialistAgent specialist) {
    }
}
