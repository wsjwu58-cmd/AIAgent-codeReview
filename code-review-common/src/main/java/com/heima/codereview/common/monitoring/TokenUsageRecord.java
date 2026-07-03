package com.heima.codereview.common.monitoring;

import java.time.Instant;

public record TokenUsageRecord(
    String sessionId,
    String agentId,
    String scene,
    String modelName,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    long latencyMs,
    Instant timestamp,
    boolean cached
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sessionId = "";
        private String agentId = "";
        private String scene = "unknown";
        private String modelName = "unknown";
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long latencyMs;
        private Instant timestamp = Instant.now();
        private boolean cached;

        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder scene(String v) { this.scene = v; return this; }
        public Builder modelName(String v) { this.modelName = v; return this; }
        public Builder promptTokens(long v) { this.promptTokens = v; return this; }
        public Builder completionTokens(long v) { this.completionTokens = v; return this; }
        public Builder totalTokens(long v) { this.totalTokens = v; return this; }
        public Builder latencyMs(long v) { this.latencyMs = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }
        public Builder cached(boolean v) { this.cached = v; return this; }

        public TokenUsageRecord build() {
            long total = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
            return new TokenUsageRecord(sessionId, agentId, scene, modelName,
                    promptTokens, completionTokens, total, latencyMs, timestamp, cached);
        }
    }
}
