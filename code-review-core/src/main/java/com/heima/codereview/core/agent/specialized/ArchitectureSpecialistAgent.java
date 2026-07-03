package com.heima.codereview.core.agent.specialized;

import com.heima.codereview.common.utils.IdUtils;
import com.heima.codereview.core.agent.AgentTextGenerator;
import com.heima.codereview.core.agent.SpecialistAgent;
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

@Component
public class ArchitectureSpecialistAgent extends SpecialistAgent {

    public ArchitectureSpecialistAgent(AgentTextGenerator textGenerator,
                                       ObjectProvider<McpToolExecutor> toolExecutorProvider,
                                       ObjectProvider<McpClient> mcpClientProvider) {
        super(textGenerator, toolExecutorProvider, mcpClientProvider);
    }

    @Override
    public String specialistId() {
        return "architecture-specialist";
    }

    @Override
    public String getName() {
        return "Architecture Specialist";
    }

    @Override
    public String specialty() {
        return "architecture";
    }

    @Override
    public List<IntentType> supportedIntents() {
        return List.of(IntentType.ARCHITECTURE_ANALYSIS);
    }

    @Override
    protected String getSpecialistSystemPrompt() {
        return "You are an architecture specialist. Focus on module boundaries, dependencies, layering, coupling, extensibility, and design trade-offs. Always answer in Simplified Chinese.";
    }

    @Override
    protected List<String> preferredToolNames() {
        return List.of(
                "git_diff_fetch",
                "file_operation",
                "code_search",
                "norm_search",
                "review_history_search"
        );
    }

    @Override
    public List<SubTask> proposeSubTasks(SubTask task, String result, ConversationContext context) {
        String normalized = result == null ? "" : result.toLowerCase(Locale.ROOT);
        List<SubTask> proposals = new ArrayList<>();
        if (containsAny(normalized, "性能", "throughput", "latency", "bottleneck", "性能")) {
            proposals.add(task.nextDepth(
                    IdUtils.withPrefix("task"),
                    IntentType.PERFORMANCE_ANALYSIS,
                    "Architecture performance check",
                    "Architecture analysis identified a potential performance bottleneck and requests a focused performance review.",
                    "performance-specialist"
            ));
        }
        if (containsAny(normalized, "文档", "diagram", "mermaid", "说明")) {
            proposals.add(task.nextDepth(
                    IdUtils.withPrefix("task"),
                    IntentType.DOCUMENTATION_GENERATION,
                    "Architecture documentation",
                    "Summarize the architecture findings into implementation-facing documentation.",
                    "documentation-specialist"
            ));
        }
        return proposals;
    }

    @Override
    protected String fallbackAnalysis(String userMessage, ReactContext context, List<ToolCallResult> toolResults) {
        StringBuilder builder = new StringBuilder();
        builder.append("结论摘要:\n");
        builder.append("- 当前任务更偏向架构分析，应重点关注模块边界、依赖方向和职责拆分。\n");
        if (context.conversationContext().hasRepositoryContext()) {
            builder.append("- 已具备仓库上下文，可以先从变更文件和核心目录入手梳理层次结构。\n");
        }
        builder.append("核心依据:\n");
        builder.append("- 已观察 ").append(toolResults.size()).append(" 条工具结果。\n");
        builder.append("下一步建议:\n");
        builder.append("- 优先补充核心模块目录或关键类名，必要时输出 Mermaid/PlantUML 结构图。");
        return builder.toString();
    }
}
