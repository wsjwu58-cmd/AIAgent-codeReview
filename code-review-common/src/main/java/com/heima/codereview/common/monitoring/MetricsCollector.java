package com.heima.codereview.common.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

public final class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();
    private static final int TOKEN_ESTIMATE_CHARS_PER_TOKEN = 3;

    private final Queue<TokenUsageRecord> tokenBuffer = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> historyBuffer = new ConcurrentLinkedQueue<>();
    private final LongAdder totalRequestCount = new LongAdder();
    private final LongAdder totalErrorCount = new LongAdder();
    private volatile boolean enabled = true;
    private volatile Instant sessionStart;

    private MetricsCollector() {
        this.sessionStart = Instant.now();
    }

    public static MetricsCollector instance() {
        return INSTANCE;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void startSession() {
        this.sessionStart = Instant.now();
        this.totalRequestCount.reset();
        this.totalErrorCount.reset();
    }

    public void recordTokenUsage(TokenUsageRecord record) {
        if (!enabled || record == null) {
            return;
        }
        tokenBuffer.offer(record);
        totalRequestCount.increment();
    }

    public void recordLatency(String agentId, String scene, String text, long latencyMs, int inputLen, int outputLen) {
        if (!enabled) {
            return;
        }
        TokenUsageRecord record = TokenUsageRecord.builder()
                .agentId(agentId)
                .scene(scene)
                .promptTokens(estimateTokens(inputLen))
                .completionTokens(estimateTokens(outputLen))
                .latencyMs(latencyMs)
                .timestamp(Instant.now())
                .build();
        recordTokenUsage(record);
    }

    public void recordError(String agentId, String reason) {
        if (!enabled) {
            return;
        }
        totalErrorCount.increment();
        historyBuffer.offer("[" + Instant.now() + "] error agent=" + agentId + " reason=" + reason);
    }

    public void recordHistory(String message) {
        if (!enabled) {
            return;
        }
        historyBuffer.offer("[" + Instant.now() + "] " + message);
    }

    public void recordEmbeddingCacheHit(String text) {
        if (!enabled) {
            return;
        }
        TokenUsageRecord record = TokenUsageRecord.builder()
                .scene("embedding")
                .promptTokens(0)
                .completionTokens(0)
                .latencyMs(0)
                .cached(true)
                .timestamp(Instant.now())
                .build();
        tokenBuffer.offer(record);
    }

    public void recordEmbeddingGeneration(String text, int estimatedChars) {
        if (!enabled) {
            return;
        }
        TokenUsageRecord record = TokenUsageRecord.builder()
                .scene("embedding")
                .promptTokens(estimateTokens(estimatedChars))
                .completionTokens(0)
                .cached(false)
                .timestamp(Instant.now())
                .build();
        tokenBuffer.offer(record);
    }

    public TokenUsageSummary summarize() {
        List<TokenUsageRecord> records = drain();
        return TokenUsageSummary.from(records, sessionStart);
    }

    public static long estimateTokens(int charCount) {
        if (charCount <= 0) {
            return 0;
        }
        return Math.max(1, charCount / TOKEN_ESTIMATE_CHARS_PER_TOKEN);
    }

    public List<String> drainHistory() {
        List<String> history = new ArrayList<>();
        String entry;
        while ((entry = historyBuffer.poll()) != null) {
            history.add(entry);
        }
        return history;
    }

    private List<TokenUsageRecord> drain() {
        List<TokenUsageRecord> records = new ArrayList<>();
        TokenUsageRecord record;
        while ((record = tokenBuffer.poll()) != null) {
            records.add(record);
        }
        return records;
    }

    public record TokenUsageSummary(
            long totalCalls,
            long errorCount,
            long totalPromptTokens,
            long totalCompletionTokens,
            long totalTokens,
            double avgLatencyMs,
            long maxLatencyMs,
            long minLatencyMs,
            long cacheHits,
            long totalEstimates,
            long sessionDurationMs,
            Map<String, Long> tokensByScene,
            Map<String, Long> tokensByAgent
    ) {
        static TokenUsageSummary from(List<TokenUsageRecord> records, Instant sessionStart) {
            if (records.isEmpty()) {
                return new TokenUsageSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        Duration.between(sessionStart, Instant.now()).toMillis(),
                        Map.of(), Map.of());
            }

            LongSummaryStatistics latencyStats = records.stream()
                    .filter(r -> r.latencyMs() > 0)
                    .mapToLong(TokenUsageRecord::latencyMs)
                    .summaryStatistics();

            long totalPrompt = records.stream().mapToLong(TokenUsageRecord::promptTokens).sum();
            long totalCompletion = records.stream().mapToLong(TokenUsageRecord::completionTokens).sum();
            long totalAll = records.stream().mapToLong(TokenUsageRecord::totalTokens).sum();
            long cacheHits = records.stream().filter(TokenUsageRecord::cached).count();

            Map<String, Long> byScene = records.stream()
                    .collect(Collectors.groupingBy(TokenUsageRecord::scene, Collectors.summingLong(TokenUsageRecord::totalTokens)));

            Map<String, Long> byAgent = records.stream()
                    .filter(r -> !r.agentId().isEmpty())
                    .collect(Collectors.groupingBy(TokenUsageRecord::agentId, Collectors.summingLong(TokenUsageRecord::totalTokens)));

            return new TokenUsageSummary(
                    records.size(), 0,
                    totalPrompt, totalCompletion, totalAll,
                    latencyStats.getAverage(), latencyStats.getMax(), latencyStats.getMin(),
                    cacheHits, 0,
                    Duration.between(sessionStart, Instant.now()).toMillis(),
                    byScene, byAgent
            );
        }

        public double estimatedCostUsd(double promptPricePer1k, double completionPricePer1k) {
            return (totalPromptTokens * promptPricePer1k + totalCompletionTokens * completionPricePer1k) / 1000.0;
        }

        public String toCompactString() {
            return String.format(
                    "calls=%d errs=%d tokens(prompt=%d compl=%d total=%d) latency(avg=%.0fms max=%dms min=%dms) cacheHits=%d duration=%dms byScene=%s",
                    totalCalls, errorCount, totalPromptTokens, totalCompletionTokens, totalTokens,
                    avgLatencyMs, maxLatencyMs, minLatencyMs,
                    cacheHits, sessionDurationMs, tokensByScene
            );
        }

        public String toPrettyString() {
            StringBuilder sb = new StringBuilder();
            sb.append("══════ Token Usage Summary ══════\n");
            sb.append(String.format("  Total Calls:        %d\n", totalCalls));
            sb.append(String.format("  Errors:             %d\n", errorCount));
            sb.append(String.format("  Prompt Tokens:      %d\n", totalPromptTokens));
            sb.append(String.format("  Completion Tokens:  %d\n", totalCompletionTokens));
            sb.append(String.format("  Total Tokens:       %d\n", totalTokens));
            sb.append(String.format("  Avg Latency:        %.0f ms\n", avgLatencyMs));
            sb.append(String.format("  Max Latency:        %d ms\n", maxLatencyMs));
            sb.append(String.format("  Cache Hits:         %d\n", cacheHits));
            sb.append(String.format("  Session Duration:   %d ms\n", sessionDurationMs));
            if (!tokensByScene.isEmpty()) {
                sb.append("  Tokens by Scene:\n");
                tokensByScene.forEach((k, v) -> sb.append(String.format("    %s: %d\n", k, v)));
            }
            if (!tokensByAgent.isEmpty()) {
                sb.append("  Tokens by Agent:\n");
                tokensByAgent.forEach((k, v) -> sb.append(String.format("    %s: %d\n", k, v)));
            }
            sb.append("══════════════════════════════════");
            return sb.toString();
        }
    }
}
