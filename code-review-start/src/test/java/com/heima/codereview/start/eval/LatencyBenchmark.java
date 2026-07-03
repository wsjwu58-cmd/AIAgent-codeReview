package com.heima.codereview.start.eval;

import com.heima.codereview.core.agent.AgentTextGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Profile("eval")
public class LatencyBenchmark {

    private static final Logger log = LoggerFactory.getLogger(LatencyBenchmark.class);

    private final AgentTextGenerator textGenerator;

    public LatencyBenchmark(AgentTextGenerator textGenerator) {
        this.textGenerator = textGenerator;
    }

    public LatencyReport benchmarkLatency(List<String> queries, int warmupRounds, int testRounds) {
        if (queries.isEmpty() || !textGenerator.available()) {
            return new LatencyReport(0, 0, 0, 0, 0, 0, 0);
        }

        for (int i = 0; i < warmupRounds; i++) {
            try {
                executeQuery(queries.get(i % queries.size()));
            } catch (Exception e) {
                log.warn("Warmup round {} failed: {}", i, e.getMessage());
            }
        }

        List<Long> latencies = new ArrayList<>();
        for (String query : queries) {
            for (int i = 0; i < testRounds; i++) {
                try {
                    long start = System.currentTimeMillis();
                    executeQuery(query);
                    latencies.add(System.currentTimeMillis() - start);
                } catch (Exception e) {
                    log.warn("Latency test failed for query: {}", query, e);
                    latencies.add(-1L);
                }
            }
        }

        List<Long> valid = latencies.stream().filter(l -> l >= 0).sorted().toList();
        if (valid.isEmpty()) {
            return new LatencyReport(0, 0, 0, 0, 0, 0, 0);
        }

        return new LatencyReport(
                valid.get(0),
                percentile(valid, 50),
                percentile(valid, 90),
                percentile(valid, 95),
                percentile(valid, 99),
                valid.get(valid.size() - 1),
                valid.stream().mapToLong(Long::longValue).average().orElse(0)
        );
    }

    private String executeQuery(String query) {
        return textGenerator.generate("quick-check",
                "Briefly analyze this code query.",
                query, Map.of("scene", "eval", "disableToolCallbacks", true));
    }

    private long percentile(List<Long> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
