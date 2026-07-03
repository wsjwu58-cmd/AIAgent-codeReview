package com.heima.codereview.start.eval;

public record LatencyReport(
    long minMs,
    long p50Ms,
    long p90Ms,
    long p95Ms,
    long p99Ms,
    long maxMs,
    double avgMs
) {
    public String toPrettyString() {
        return String.format(
                "min=%dms P50=%dms P90=%dms P95=%dms P99=%dms max=%dms avg=%.0fms",
                minMs, p50Ms, p90Ms, p95Ms, p99Ms, maxMs, avgMs
        );
    }
}
