package com.heima.codereview.core.agent.specialized;

import com.heima.codereview.core.agent.AgentTextGenerator;
import com.heima.codereview.core.agent.SpecialistAgent;
import com.heima.codereview.core.agent.planning.IntentType;
import com.heima.codereview.core.agent.react.ReactContext;
import com.heima.codereview.core.agent.react.ReactState;
import com.heima.codereview.core.agent.react.ToolCallResult;
import com.heima.codereview.tools.mcp.McpClient;
import com.heima.codereview.tools.mcp.McpToolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PerformanceSpecialistAgent extends SpecialistAgent {

    public PerformanceSpecialistAgent(AgentTextGenerator textGenerator,
                                      ObjectProvider<McpToolExecutor> toolExecutorProvider,
                                      ObjectProvider<McpClient> mcpClientProvider) {
        super(textGenerator, toolExecutorProvider, mcpClientProvider);
    }

    @Override
    public String specialistId() {
        return "performance-specialist";
    }

    @Override
    public String getName() {
        return "Performance Specialist";
    }

    @Override
    public String specialty() {
        return "performance";
    }

    @Override
    public List<IntentType> supportedIntents() {
        return List.of(IntentType.PERFORMANCE_ANALYSIS);
    }

    @Override
    protected String getSpecialistSystemPrompt() {
        return "You are a performance specialist. Focus on algorithmic complexity, hot loops, repeated queries, blocking calls, and memory pressure. Always answer in Simplified Chinese.";
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

    @Override
    protected int maxToolCalls() {
        return 4;
    }

    @Override
    public boolean shouldTerminate(ReactState state) {
        return super.shouldTerminate(state);
    }

    @Override
    protected String fallbackAnalysis(String userMessage, ReactContext context, List<ToolCallResult> toolResults) {
        StringBuilder builder = new StringBuilder();
        builder.append("发现或判断:\n");
        builder.append("- 需要重点检查重复计算、频繁 I/O 与数据库访问链路。\n");
        if (containsAny(userMessage, "sql", "query", "db", "database")) {
            builder.append("- 当前问题明显涉及数据访问性能，建议优先确认索引和批量加载策略。\n");
        }
        builder.append("依据:\n");
        builder.append("- 已综合 ").append(toolResults.size()).append(" 条性能相关证据。\n");
        builder.append("建议动作:\n");
        builder.append("- 对热点路径做基准测试，并优先优化高频循环与查询。\n");
        return builder.toString();
    }
}
