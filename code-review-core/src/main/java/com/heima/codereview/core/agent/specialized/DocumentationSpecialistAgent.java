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
public class DocumentationSpecialistAgent extends SpecialistAgent {

    public DocumentationSpecialistAgent(AgentTextGenerator textGenerator,
                                        ObjectProvider<McpToolExecutor> toolExecutorProvider,
                                        ObjectProvider<McpClient> mcpClientProvider) {
        super(textGenerator, toolExecutorProvider, mcpClientProvider);
    }

    @Override
    public String specialistId() {
        return "documentation-specialist";
    }

    @Override
    public String getName() {
        return "Documentation Specialist";
    }

    @Override
    public String specialty() {
        return "documentation";
    }

    @Override
    public List<IntentType> supportedIntents() {
        return List.of(IntentType.DOCUMENTATION_GENERATION);
    }

    @Override
    protected String getSpecialistSystemPrompt() {
        return "You are a documentation specialist. Produce concise, implementation-facing documentation, code explanations, README sections, and API summaries in Simplified Chinese.";
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
        if (containsAny(normalized, "缺少代码", "need code", "unknown implementation", "无法确认")) {
            proposals.add(task.nextDepth(
                    IdUtils.withPrefix("task"),
                    IntentType.CODE_REVIEW,
                    "Collect code evidence",
                    "Documentation generation still needs concrete code evidence or implementation details.",
                    "review-specialist"
            ));
        }
        return proposals;
    }

    @Override
    protected String fallbackAnalysis(String userMessage, ReactContext context, List<ToolCallResult> toolResults) {
        StringBuilder builder = new StringBuilder();
        builder.append("文档方向建议:\n");
        builder.append("- 可围绕用途、核心流程、关键模块、输入输出和限制条件组织文档。\n");
        builder.append("证据来源:\n");
        builder.append("- 已汇总 ").append(toolResults.size()).append(" 条工具结果。\n");
        builder.append("后续动作:\n");
        builder.append("- 如果需要 README、接口说明或架构说明，可直接指定目标文档类型。");
        return builder.toString();
    }
}
