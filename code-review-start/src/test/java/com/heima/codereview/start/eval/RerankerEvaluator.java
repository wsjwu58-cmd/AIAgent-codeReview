package com.heima.codereview.start.eval;

import com.heima.codereview.rag.model.ReviewRecord;
import com.heima.codereview.rag.retrieval.HybridSearch;
import com.heima.codereview.rag.retrieval.ReviewReranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
@Profile("eval")
public class RerankerEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RerankerEvaluator.class);

    private final ReviewReranker reranker;
    private final HybridSearch hybridSearch;
    private final HybridSearchEvaluator searchEvaluator;

    public RerankerEvaluator(ReviewReranker reranker, HybridSearch hybridSearch,
                             HybridSearchEvaluator searchEvaluator) {
        this.reranker = reranker;
        this.hybridSearch = hybridSearch;
        this.searchEvaluator = searchEvaluator;
    }

    public record RerankerEvalResult(
            String queryId,
            double preNdcg,
            double postNdcg,
            double improvement
    ) {
        public String toPrettyString() {
            return String.format(
                    "Query=%s preNDCG=%.3f postNDCG=%.3f improvement=%.3f",
                    queryId, preNdcg, postNdcg, improvement
            );
        }
    }

    public RerankerEvalResult evaluateReranker(RagTestQuery testQuery) {
        List<ReviewRecord> preRank;
        try {
            preRank = hybridSearch.search(testQuery.query(), testQuery.projectId(), null, 20);
        } catch (Exception e) {
            log.warn("Pre-rank search failed: {}", e.getMessage());
            preRank = List.of();
        }

        double preNdcg = searchEvaluator.computeNdcg(preRank, testQuery, 10);

        List<ReviewRecord> postRank;
        try {
            postRank = reranker.rerank(testQuery.query(), preRank, 10);
        } catch (Exception e) {
            log.warn("Rerank failed: {}", e.getMessage());
            postRank = preRank;
        }

        double postNdcg = searchEvaluator.computeNdcg(postRank, testQuery, 10);

        return new RerankerEvalResult(testQuery.queryId(), preNdcg, postNdcg, postNdcg - preNdcg);
    }

    public double evaluateBatch(List<RagTestQuery> queries) {
        double totalImprovement = 0.0;
        for (RagTestQuery q : queries) {
            RerankerEvalResult result = evaluateReranker(q);
            totalImprovement += result.improvement();
            log.info("Reranker: {}", result.toPrettyString());
        }
        return totalImprovement / Math.max(1, queries.size());
    }
}
