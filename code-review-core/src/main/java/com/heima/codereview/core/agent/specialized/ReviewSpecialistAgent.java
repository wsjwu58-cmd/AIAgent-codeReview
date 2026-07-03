package com.heima.codereview.core.agent.specialized;

import com.heima.codereview.common.model.review.ReviewIssue;
import com.heima.codereview.common.model.review.ReviewReport;
import com.heima.codereview.common.utils.IdUtils;
import com.heima.codereview.core.agent.AdvisorAgent;
import com.heima.codereview.core.agent.AgentEventListener;
import com.heima.codereview.core.agent.AgentExecutionContext;
import com.heima.codereview.core.agent.AgentTextGenerator;
import com.heima.codereview.core.agent.RefactorAgent;
import com.heima.codereview.core.agent.ReviewAgent;
import com.heima.codereview.core.agent.SpecialistAgent;
import com.heima.codereview.core.agent.SummarizerAgent;
import com.heima.codereview.core.agent.conversational.ConversationContext;
import com.heima.codereview.core.agent.planning.IntentType;
import com.heima.codereview.core.agent.planning.SubTask;
import com.heima.codereview.core.agent.react.ReactContext;
import com.heima.codereview.core.agent.react.ToolCallResult;
import com.heima.codereview.tools.mcp.McpClient;
import com.heima.codereview.tools.mcp.McpToolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ReviewSpecialistAgent extends SpecialistAgent {

    private final ReviewAgent reviewAgent;
    private final AdvisorAgent advisorAgent;
    private final RefactorAgent refactorAgent;
    private final SummarizerAgent summarizerAgent;

    public ReviewSpecialistAgent(AgentTextGenerator textGenerator,
                                 ObjectProvider<McpToolExecutor> toolExecutorProvider,
                                 ObjectProvider<McpClient> mcpClientProvider,
                                 ReviewAgent reviewAgent,
                                 AdvisorAgent advisorAgent,
                                 RefactorAgent refactorAgent,
                                 SummarizerAgent summarizerAgent) {
        super(textGenerator, toolExecutorProvider, mcpClientProvider);
        this.reviewAgent = reviewAgent;
        this.advisorAgent = advisorAgent;
        this.refactorAgent = refactorAgent;
        this.summarizerAgent = summarizerAgent;
    }

    @Override
    public String specialistId() {
        return "review-specialist";
    }

    @Override
    public String getName() {
        return "Review Specialist";
    }

    @Override
    public String specialty() {
        return "code-review";
    }

    @Override
    public List<IntentType> supportedIntents() {
        return List.of(IntentType.CODE_REVIEW);
    }

    @Override
    protected String getSpecialistSystemPrompt() {
        return "You are a code review specialist. Focus on correctness, maintainability, regressions, and code quality. Always answer in Simplified Chinese.";
    }

    @Override
    protected List<String> preferredToolNames() {
        return List.of(
                "git_diff_fetch",
                "review_history_search",
                "session_memory_read",
                "chat_history_search",
                "norm_search",
                "code_search"
        );
    }

    public ReviewSpecialistOutcome review(AgentExecutionContext context, AgentEventListener listener) {
        ReviewReport reviewReport = reviewAgent.execute(context, (index, node) -> {
            if (listener != null) {
                listener.onEvent(context.sessionId(), "chain_node", Map.of(
                        "current", index + 1,
                        "nodeName", node.getName()
                ));
            }
        });
        String advisorOutput = advisorAgent.execute(context.reviewContext(), reviewReport);
        streamText(context.sessionId(), advisorAgent.getId(), advisorOutput, listener);
        String refactoredCode = refactorAgent.execute(context.reviewContext(), advisorOutput);
        streamText(context.sessionId(), refactorAgent.getId(), refactoredCode, listener);
        String summary = summarizerAgent.execute(context.reviewContext(), reviewReport, advisorOutput, refactoredCode);
        streamText(context.sessionId(), summarizerAgent.getId(), summary, listener);
        return new ReviewSpecialistOutcome(
                reviewReport,
                advisorOutput,
                refactoredCode,
                summary,
                proposeReviewSubTasks(reviewReport)
        );
    }

    @Override
    public List<SubTask> proposeSubTasks(SubTask task, String result, ConversationContext context) {
        String normalized = result == null ? "" : result.toLowerCase(Locale.ROOT);
        List<SubTask> proposals = new ArrayList<>();
        if (containsAny(normalized, "security", "漏洞", "敏感", "注入", "secret")) {
            proposals.add(task.nextDepth(
                    IdUtils.withPrefix("task"),
                    IntentType.SECURITY_ANALYSIS,
                    "Deepen security review",
                    "Review specialist found security-related signals and requests a focused security pass.",
                    "security-specialist"
            ));
        }
        if (containsAny(normalized, "performance", "慢", "查询", "复杂度", "热点")) {
            proposals.add(task.nextDepth(
                    IdUtils.withPrefix("task"),
                    IntentType.PERFORMANCE_ANALYSIS,
                    "Deepen performance review",
                    "Review specialist found performance-related signals and requests a focused performance pass.",
                    "performance-specialist"
            ));
        }
        return proposals;
    }

    @Override
    protected String fallbackAnalysis(String userMessage, ReactContext context, List<ToolCallResult> toolResults) {
        StringBuilder builder = new StringBuilder();
        builder.append("发现或判断:\n");
        if (context.conversationContext().hasRepositoryContext()) {
            builder.append("- 已获取仓库差异，可围绕最近改动开展审查。\n");
        } else if (looksLikeCode(userMessage)) {
            builder.append("- 当前更像是代码片段审查，应重点关注结构、边界和可维护性。\n");
        } else {
            builder.append("- 当前缺少足够源码上下文，结论以审查思路为主。\n");
        }
        builder.append("依据:\n");
        builder.append("- 已观察 ").append(toolResults.size()).append(" 条工具结果。\n");
        builder.append("建议动作:\n");
        builder.append("- 优先补充目标模块、异常栈或仓库差异范围，再继续逐项审查。");
        return builder.toString();
    }

    private List<SubTask> proposeReviewSubTasks(ReviewReport report) {
        List<SubTask> tasks = new ArrayList<>();
        if (report == null || report.getIssues() == null) {
            return tasks;
        }
        boolean hasSecurityIssue = report.getIssues().stream()
                .map(ReviewIssue::ruleId)
                .filter(item -> item != null)
                .anyMatch(item -> item.toUpperCase(Locale.ROOT).startsWith("SEC_"));
        boolean hasPerformanceIssue = report.getIssues().stream()
                .map(ReviewIssue::ruleId)
                .filter(item -> item != null)
                .anyMatch(item -> item.toUpperCase(Locale.ROOT).startsWith("PERF_"));
        if (hasSecurityIssue) {
            tasks.add(SubTask.of(
                    IdUtils.withPrefix("task"),
                    IntentType.SECURITY_ANALYSIS,
                    "Security follow-up",
                    "Review chain found security issues. Run a focused security analysis.",
                    "security-specialist",
                    1,
                    ""
            ));
        }
        if (hasPerformanceIssue) {
            tasks.add(SubTask.of(
                    IdUtils.withPrefix("task"),
                    IntentType.PERFORMANCE_ANALYSIS,
                    "Performance follow-up",
                    "Review chain found performance issues. Run a focused performance analysis.",
                    "performance-specialist",
                    1,
                    ""
            ));
        }
        return tasks;
    }

    private void streamText(String sessionId, String agentId, String content, AgentEventListener listener) {
        if (listener == null || content == null) {
            return;
        }
        for (char c : content.toCharArray()) {
            listener.onEvent(sessionId, "agent_stream", Map.of("agentId", agentId, "content", String.valueOf(c)));
        }
    }
}
