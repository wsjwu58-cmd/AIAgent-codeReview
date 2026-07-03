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
public class SecuritySpecialistAgent extends SpecialistAgent {

    public SecuritySpecialistAgent(AgentTextGenerator textGenerator,
                                   ObjectProvider<McpToolExecutor> toolExecutorProvider,
                                   ObjectProvider<McpClient> mcpClientProvider) {
        super(textGenerator, toolExecutorProvider, mcpClientProvider);
    }

    @Override
    public String specialistId() {
        return "security-specialist";
    }

    @Override
    public String getName() {
        return "Security Specialist";
    }

    @Override
    public String specialty() {
        return "security";
    }

    @Override
    public List<IntentType> supportedIntents() {
        return List.of(IntentType.SECURITY_ANALYSIS);
    }

    @Override
    protected String getSpecialistSystemPrompt() {
        return "You are a security specialist. Focus on secrets, injection, authorization gaps, sensitive data exposure, and dangerous execution paths. Always answer in Simplified Chinese.";
    }

    @Override
    protected List<String> preferredToolNames() {
        return List.of(
                "git_diff_fetch",
                "review_history_search",
                "session_memory_read",
                "chat_history_search",
                "norm_search",
                "code_search",
                "web_search"
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
        if (toolResults.isEmpty()) {
            builder.append("- 当前没有直接命中的高风险信号，但仍需检查输入校验、鉴权和敏感配置。\n");
        } else {
            builder.append("- 已发现 ").append(toolResults.size()).append(" 条安全相关证据，应优先核查高危路径。\n");
        }
        builder.append("依据:\n");
        builder.append("- 结论综合了问题描述与工具返回结果。\n");
        builder.append("建议动作:\n");
        builder.append("- 针对外部输入、认证授权链路和敏感信息处理补充专项检查。");
        return builder.toString();
    }
}
