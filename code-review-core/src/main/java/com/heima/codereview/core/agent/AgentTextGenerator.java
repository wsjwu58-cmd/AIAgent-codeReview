package com.heima.codereview.core.agent;

import java.util.Map;
import java.util.function.Consumer;

public interface AgentTextGenerator {

    default String generate(String agentName, String instruction, String input) {
        return generate(agentName, instruction, input, Map.of());
    }

    String generate(String agentName, String instruction, String input, Map<String, Object> context);

    /**
     * 流式生成：每个 chunk 通过 chunkHandler 推出，最终聚合为完整字符串返回。
     * chunkHandler 为 null 时退化为非流式调用。
     */
    default String generate(String agentName,
                            String instruction,
                            String input,
                            Map<String, Object> context,
                            Consumer<String> chunkHandler) {
        String result = generate(agentName, instruction, input, context);
        if (chunkHandler != null && result != null && !result.isBlank()) {
            chunkHandler.accept(result);
        }
        return result;
    }

    default boolean available() {
        return false;
    }
}
