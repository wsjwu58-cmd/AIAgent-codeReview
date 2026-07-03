package com.heima.codereview.start.eval;

import com.heima.codereview.rag.model.ReviewRecord;
import com.heima.codereview.rag.retrieval.QueryRewriter;
import com.heima.codereview.rag.retrieval.HybridSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile("eval")
public class QueryRewriterEvaluator {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriterEvaluator.class);

    private final QueryRewriter queryRewriter;
    private final HybridSearch hybridSearch;

    public QueryRewriterEvaluator(QueryRewriter queryRewriter, HybridSearch hybridSearch) {
        this.queryRewriter = queryRewriter;
        this.hybridSearch = hybridSearch;
    }

    public record RewriteEvalResult(
            String queryId,
            double originalRecall,
            double rewrittenRecall,
            double improvement,
            List<String> rewrittenQueries
    ) {
        public String toPrettyString() {
            return String.format(
                    "Query=%s originalRecall=%.1f%% rewrittenRecall=%.1f%% improvement=%.1f%% rewritten=%s",
                    queryId, originalRecall * 100, rewrittenRecall * 100, improvement * 100, rewrittenQueries
            );
        }
    }

    public RewriteEvalResult evaluateRewrite(RagTestQuery testQuery) {
        double originalRecall;
        try {
            List<ReviewRecord> originalResults = hybridSearch.search(
                    testQuery.query(), testQuery.projectId(), null, 10);
            originalRecall = computeRecall(originalResults, testQuery.relevantChunkIds(), 10);
        } catch (Exception e) {
            log.warn("Original search failed: {}", e.getMessage());
            originalRecall = 0.0;
        }

        double rewrittenRecall;
        List<String> rewrittenQueries;
        try {
            rewrittenQueries = queryRewriter.rewrite(testQuery.query());
        } catch (Exception e) {
            log.warn("Query rewriting failed: {}", e.getMessage());
            rewrittenQueries = List.of(testQuery.query());
        }

        try {
            List<ReviewRecord> rewrittenResults = new ArrayList<>();
            for (String rewritten : rewrittenQueries) {
                List<ReviewRecord> subResults = hybridSearch.search(
                        rewritten, testQuery.projectId(), null, 5);
                rewrittenResults.addAll(subResults);
            }
            rewrittenResults = deduplicate(rewrittenResults);
            rewrittenRecall = computeRecall(rewrittenResults, testQuery.relevantChunkIds(), 10);
        } catch (Exception e) {
            log.warn("Rewritten search failed: {}", e.getMessage());
            rewrittenRecall = 0.0;
        }

        return new RewriteEvalResult(
                testQuery.queryId(),
                originalRecall,
                rewrittenRecall,
                rewrittenRecall - originalRecall,
                rewrittenQueries
        );
    }

    public double evaluateBatch(List<RagTestQuery> queries) {
        double totalImprovement = 0.0;
        for (RagTestQuery q : queries) {
            RewriteEvalResult result = evaluateRewrite(q);
            totalImprovement += result.improvement();
            log.info("QueryRewrite: {}", result.toPrettyString());
        }
        return totalImprovement / Math.max(1, queries.size());
    }

    private double computeRecall(List<ReviewRecord> results, java.util.Map<String, Double> relevantIds, int k) {
        Set<String> retrievedIds = results.stream()
                .limit(k).map(ReviewRecord::id).collect(Collectors.toSet());
        long hits = relevantIds.keySet().stream().filter(retrievedIds::contains).count();
        return relevantIds.isEmpty() ? 1.0 : (double) hits / relevantIds.size();
    }

    private List<ReviewRecord> deduplicate(List<ReviewRecord> results) {
        return results.stream()
                .collect(Collectors.toMap(ReviewRecord::id, r -> r, (a, b) -> a))
                .values().stream().toList();
    }
}
