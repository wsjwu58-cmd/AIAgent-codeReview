package com.heima.codereview.core.agent.conversational;

import com.heima.codereview.core.agent.react.ReactResult;
import com.heima.codereview.core.agent.react.ThinkingStep;

public interface ReactStreamListener {

    default void onAgentStart(String agentId, String agentName) {
    }

    default void onStep(ThinkingStep step) {
    }

    default void onAgentComplete(SpecialistReport report) {
    }

    default void onComplete(ReactResult result) {
    }

    default void onError(String message, Throwable throwable) {
    }

    /**
     * LLM 流式生成的增量 chunk。agentId 标识产生内容的 agent，chunk 是本次推送的文本片段。
     */
    default void onAgentStream(String agentId, String chunk) {
    }
}
