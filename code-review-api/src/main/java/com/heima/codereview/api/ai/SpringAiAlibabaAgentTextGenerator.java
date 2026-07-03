package com.heima.codereview.api.ai;

import com.heima.codereview.core.agent.AgentTextGenerator;
import com.heima.codereview.common.monitoring.MetricsCollector;
import com.heima.codereview.tools.mcp.McpToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
@Primary
public class SpringAiAlibabaAgentTextGenerator implements AgentTextGenerator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAlibabaAgentTextGenerator.class);
    private static final int LOG_PREVIEW_MAX = 160;
    private static final int LOG_OUTPUT_MAX = 4000;

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final McpToolExecutor mcpToolExecutor;

    public SpringAiAlibabaAgentTextGenerator(ObjectProvider<ChatModel> chatModelProvider,
                                             McpToolExecutor mcpToolExecutor) {
        this.chatModelProvider = chatModelProvider;
        this.mcpToolExecutor = mcpToolExecutor;
    }

    @Override
    public String generate(String agentName, String instruction, String input, Map<String, Object> context) {
        long start = System.currentTimeMillis();
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        boolean disableToolCallbacks = isToolCallbackDisabled(safeContext);
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("AI dialog degraded because no ChatModel is available. agentName={}, instructionLength={}, inputLength={}, inputPreview={}",
                    agentName, safeLength(instruction), safeLength(input), preview(input));
            return "";
        }

        log.info("AI dialog started. agentName={}, instructionLength={}, inputLength={}, inputPreview={}",
                agentName, safeLength(instruction), safeLength(input), preview(input));
        try {
            String systemPrompt = "Current agent: " + agentName + "\n" + defaultString(instruction)
                    + (disableToolCallbacks
                    ? "\nTool callbacks are disabled for this request. Reason only with the provided context."
                    : "\nIf the task needs external context, history, or repository code, proactively call the available tools.")
                    + "\nAlways answer in Simplified Chinese unless the instruction explicitly requires another language.";
            String output = ChatClient.create(chatModel)
                    .prompt()
                    .system(systemPrompt)
                    .user(input)
                    .toolCallbacks(disableToolCallbacks ? List.of() : buildToolCallbacks(safeContext))
                    .toolContext(safeContext)
                    .call()
                    .content();
            if (output == null || output.isBlank()) {
                log.warn("AI dialog returned empty content. agentName={}, costMs={}", agentName, System.currentTimeMillis() - start);
                return "";
            }
            long costMs = System.currentTimeMillis() - start;
            log.info("AI dialog finished. agentName={}, costMs={}, outputLength={}, outputPreview={}",
                    agentName, costMs, output.length(), preview(output));
            log.info("AI dialog output. agentName={}, output={}", agentName, outputForLog(output));
            MetricsCollector.instance().recordLatency(agentName, sceneFromContext(safeContext),
                    output, costMs, safeLen(instruction) + safeLen(input), output.length());
            return output;
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            log.error("AI dialog failed. agentName={}, costMs={}, reason={}",
                    agentName, costMs, e.getMessage(), e);
            MetricsCollector.instance().recordError(agentName, e.getMessage());
            return "";
        }
    }

    @Override
    public boolean available() {
        return chatModelProvider.getIfAvailable() != null;
    }

    @Override
    public String generate(String agentName,
                           String instruction,
                           String input,
                           Map<String, Object> context,
                           Consumer<String> chunkHandler) {
        if (chunkHandler == null) {
            return generate(agentName, instruction, input, context);
        }
        long start = System.currentTimeMillis();
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        boolean disableToolCallbacks = isToolCallbackDisabled(safeContext);
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("AI stream dialog degraded because no ChatModel is available. agentName={}, inputLength={}, inputPreview={}",
                    agentName, safeLength(input), preview(input));
            return "";
        }

        log.info("AI stream dialog started. agentName={}, instructionLength={}, inputLength={}, inputPreview={}",
                agentName, safeLength(instruction), safeLength(input), preview(input));
        try {
            String systemPrompt = "Current agent: " + agentName + "\n" + defaultString(instruction)
                    + (disableToolCallbacks
                    ? "\nTool callbacks are disabled for this request. Reason only with the provided context."
                    : "\nIf the task needs external context, history, or repository code, proactively call the available tools.")
                    + "\nAlways answer in Simplified Chinese unless the instruction explicitly requires another language.";
            StringBuilder accumulator = new StringBuilder();
            Flux<String> streamFlux = ChatClient.create(chatModel)
                    .prompt()
                    .system(systemPrompt)
                    .user(input)
                    .toolCallbacks(disableToolCallbacks ? List.of() : buildToolCallbacks(safeContext))
                    .toolContext(safeContext)
                    .stream()
                    .content();
            streamFlux.doOnNext(chunk -> {
                        if (chunk != null && !chunk.isEmpty()) {
                            accumulator.append(chunk);
                            chunkHandler.accept(chunk);
                        }
                    })
                    .blockLast();
            String output = accumulator.toString();
            if (output.isBlank()) {
                log.warn("AI stream dialog returned empty content. agentName={}, costMs={}", agentName, System.currentTimeMillis() - start);
                return "";
            }
            long costMs = System.currentTimeMillis() - start;
            log.info("AI stream dialog finished. agentName={}, costMs={}, outputLength={}, outputPreview={}",
                    agentName, costMs, output.length(), preview(output));
            MetricsCollector.instance().recordLatency(agentName, sceneFromContext(safeContext),
                    output, costMs, safeLen(instruction) + safeLen(input), output.length());
            return output;
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - start;
            log.error("AI stream dialog failed. agentName={}, costMs={}, reason={}",
                    agentName, costMs, e.getMessage(), e);
            MetricsCollector.instance().recordError(agentName, e.getMessage());
            return "";
        }
    }

    private static int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    private static int safeLen(String text) {
        return safeLength(text);
    }

    private static String sceneFromContext(Map<String, Object> context) {
        if (context == null) {
            return "unknown";
        }
        Object scene = context.get("scene");
        return scene != null ? String.valueOf(scene) : "chat";
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r", " ").replace("\n", " ").trim();
        if (normalized.length() <= LOG_PREVIEW_MAX) {
            return normalized;
        }
        return normalized.substring(0, LOG_PREVIEW_MAX) + "...";
    }

    private static String outputForLog(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r", " ").replace("\n", "\\n").trim();
        if (normalized.length() <= LOG_OUTPUT_MAX) {
            return normalized;
        }
        return normalized.substring(0, LOG_OUTPUT_MAX) + "...(truncated)";
    }

    private List<ToolCallback> buildToolCallbacks(Map<String, Object> context) {
        List<ToolCallback> callbacks = new ArrayList<>();
        // 仓库审查场景下禁用所有本地相关工具回调：local_file_* 直读审查服务器磁盘，
        // file_operation 把 repoUrl 当本地路径解析（FileOperationTool.resolveBasePath），
        // 都不访问被审查的远程仓库，会污染审查依据。
        boolean repositoryReview = hasNonBlankContext(context, "repoUrl");
        callbacks.add(FunctionToolCallback.builder("review_history_search", (ReviewHistoryToolRequest request, ToolContext toolContext) ->
                        mcpToolExecutor.execute("review_history_search", Map.of(
                                "query", defaultString(request.query()),
                                "projectId", stringFromContext(toolContext, "projectId"),
                                "sessionId", stringFromContext(toolContext, "sessionId"),
                                "topK", request.topK() == null ? 5 : request.topK()
                        )))
                .description("Search related review history for the current project or session.")
                .inputType(ReviewHistoryToolRequest.class)
                .build());
        callbacks.add(FunctionToolCallback.builder("session_memory_read", (SessionMemoryToolRequest request, ToolContext toolContext) ->
                        mcpToolExecutor.execute("session_memory_read", Map.of(
                                "sessionId", stringFromContext(toolContext, "sessionId")
                        )))
                .description("Read recent chat memory for the current session.")
                .inputType(SessionMemoryToolRequest.class)
                .build());
        callbacks.add(FunctionToolCallback.builder("chat_history_search", (ChatHistoryToolRequest request, ToolContext toolContext) ->
                        mcpToolExecutor.execute("chat_history_search", Map.of(
                                "query", defaultString(request.query()),
                                "sessionId", stringFromContext(toolContext, "sessionId"),
                                "topK", request.topK() == null ? 5 : request.topK()
                        )))
                .description("Search semantically related chat history outside the current turn.")
                .inputType(ChatHistoryToolRequest.class)
                .build());
        callbacks.add(FunctionToolCallback.builder("norm_search", (NormSearchToolRequest request, ToolContext toolContext) ->
                        mcpToolExecutor.execute("norm_search", Map.of(
                                "query", defaultString(request.query()),
                                "projectId", firstNonBlank(request.projectId(), stringFromContext(toolContext, "projectId")),
                                "limit", request.limit() == null ? 5 : request.limit()
                        )))
                .description("Search uploaded PDF norms or project standards.")
                .inputType(NormSearchToolRequest.class)
                .build());
        callbacks.add(FunctionToolCallback.builder("code_refactor", (CodeRefactorToolRequest request, ToolContext toolContext) ->
                        mcpToolExecutor.execute("code_refactor", Map.of(
                                "originalCode", defaultString(request.originalCode()),
                                "suggestions", request.suggestions() == null ? List.of() : request.suggestions()
                        )))
                .description("Generate refactoring output from code and suggestions.")
                .inputType(CodeRefactorToolRequest.class)
                .build());
        if (!repositoryReview) {
            callbacks.add(FunctionToolCallback.builder("file_operation", (FileOperationToolRequest request, ToolContext toolContext) ->
                            mcpToolExecutor.execute("file_operation", Map.of(
                                    "repoUrl", firstNonBlank(request.repoUrl(), stringFromContext(toolContext, "repoUrl")),
                                    "path", defaultString(request.path()),
                                    "action", firstNonBlank(request.action(), "list"),
                                    "keyword", defaultString(request.keyword()),
                                    "limit", request.limit() == null ? 20 : request.limit()
                            )))
                    .description("List or read local repository files.")
                    .inputType(FileOperationToolRequest.class)
                    .build());
            callbacks.add(FunctionToolCallback.builder("local_file_list", (LocalFileListToolRequest request, ToolContext toolContext) ->
                            mcpToolExecutor.execute("local_file_list", Map.of(
                                    "path", defaultString(request.path()),
                                    "filters", defaultStringList(request.filters())
                            )))
                    .description("List files under an allowed local directory using optional glob filters.")
                    .inputType(LocalFileListToolRequest.class)
                    .build());
            callbacks.add(FunctionToolCallback.builder("local_file_read", (LocalFileReadToolRequest request, ToolContext toolContext) ->
                            mcpToolExecutor.execute("local_file_read", Map.of(
                                    "path", defaultString(request.path())
                            )))
                    .description("Read a file from an allowed local directory.")
                    .inputType(LocalFileReadToolRequest.class)
                    .build());
            callbacks.add(FunctionToolCallback.builder("local_file_write", (LocalFileWriteToolRequest request, ToolContext toolContext) ->
                            mcpToolExecutor.execute("local_file_write", Map.of(
                                    "path", defaultString(request.path()),
                                    "content", defaultString(request.content())
                            )))
                    .description("Write or overwrite a file in an allowed local directory.")
                    .inputType(LocalFileWriteToolRequest.class)
                    .build());
            callbacks.add(FunctionToolCallback.builder("local_file_search", (LocalFileSearchToolRequest request, ToolContext toolContext) ->
                            mcpToolExecutor.execute("local_file_search", Map.of(
                                    "path", defaultString(request.path()),
                                    "pattern", defaultString(request.pattern()),
                                    "filters", defaultStringList(request.filters())
                            )))
                    .description("Search files in an allowed local directory by content pattern and optional glob filters.")
                    .inputType(LocalFileSearchToolRequest.class)
                    .build());
            callbacks.add(FunctionToolCallback.builder("local_file_delete", (LocalFileDeleteToolRequest request, ToolContext toolContext) ->
                            mcpToolExecutor.execute("local_file_delete", Map.of(
                                    "path", defaultString(request.path())
                            )))
                    .description("Delete a file in an allowed local directory.")
                    .inputType(LocalFileDeleteToolRequest.class)
                    .build());
        }
        callbacks.add(FunctionToolCallback.builder("code_search", (CodeSearchToolRequest request, ToolContext toolContext) ->
                        mcpToolExecutor.execute("code_search", Map.of(
                                "repoUrl", firstNonBlank(request.repoUrl(), stringFromContext(toolContext, "repoUrl")),
                                "query", defaultString(request.query()),
                                "language", firstNonBlank(request.language(), stringFromContext(toolContext, "language")),
                                "limit", request.limit() == null ? 10 : request.limit()
                        )))
                .description("Search source code under the current repository.")
                .inputType(CodeSearchToolRequest.class)
                .build());
        callbacks.add(FunctionToolCallback.builder("web_search", (WebSearchToolRequest request, ToolContext toolContext) ->
                        mcpToolExecutor.execute("web_search", Map.of(
                                "query", defaultString(request.query()),
                                "limit", request.limit() == null ? 5 : request.limit()
                        )))
                .description("Search external web resources when a provider is configured.")
                .inputType(WebSearchToolRequest.class)
                .build());
        if (repositoryReview) {
            callbacks.add(FunctionToolCallback.builder("git_diff_fetch", (GitDiffToolRequest request, ToolContext toolContext) ->
                            mcpToolExecutor.execute("git_diff_fetch", Map.of(
                                    "repoUrl", firstNonBlank(request.repoUrl(), stringFromContext(toolContext, "repoUrl")),
                                    "branch", firstNonBlank(request.branch(), stringFromContext(toolContext, "branch")),
                                    "language", firstNonBlank(request.language(), stringFromContext(toolContext, "language"))
                            )))
                    .description("Fetch repository diff using repoUrl, branch, and optional language from context or tool input.")
                    .inputType(GitDiffToolRequest.class)
                    .build());
        }
        return callbacks;
    }

    private static String stringFromContext(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return "";
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return defaultString(second);
    }

    private static String defaultString(String text) {
        return text == null ? "" : text;
    }

    private static List<String> defaultStringList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean hasNonBlankContext(Map<String, Object> context, String key) {
        Object value = context.get(key);
        return value != null && !String.valueOf(value).isBlank();
    }

    private static boolean isToolCallbackDisabled(Map<String, Object> context) {
        Object value = context.get("disableToolCallbacks");
        return value instanceof Boolean disabled && disabled;
    }

    private record GitDiffToolRequest(String repoUrl, String branch, String language) {
    }

    private record CodeRefactorToolRequest(String originalCode, List<String> suggestions) {
    }

    private record ReviewHistoryToolRequest(String query, String projectId, String sessionId, Integer topK) {
    }

    private record SessionMemoryToolRequest(String sessionId) {
    }

    private record ChatHistoryToolRequest(String query, Integer topK) {
    }

    private record NormSearchToolRequest(String query, String projectId, Integer limit) {
    }

    private record FileOperationToolRequest(String repoUrl, String path, String action, String keyword, Integer limit) {
    }

    private record LocalFileListToolRequest(String path, List<String> filters) {
    }

    private record LocalFileReadToolRequest(String path) {
    }

    private record LocalFileWriteToolRequest(String path, String content) {
    }

    private record LocalFileSearchToolRequest(String path, String pattern, List<String> filters) {
    }

    private record LocalFileDeleteToolRequest(String path) {
    }

    private record CodeSearchToolRequest(String repoUrl, String query, String language, Integer limit) {
    }

    private record WebSearchToolRequest(String query, Integer limit) {
    }
}
