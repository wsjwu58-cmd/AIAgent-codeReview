package com.heima.codereview.start.eval;

public record RetrievalMetrics(
    double recall,
    double precision,
    double mrr,
    double ndcg,
    int hitCount,
    int totalRelevant
) {
    public static RetrievalMetrics empty() {
        return new RetrievalMetrics(0.0, 0.0, 0.0, 0.0, 0, 0);
    }

    public String toPrettyString() {
        return String.format(
                "Recall=%.2f%% Precision=%.2f%% MRR=%.3f NDCG=%.3f Hits=%d/%d",
                recall * 100, precision * 100, mrr, ndcg, hitCount, totalRelevant
        );
    }
}
