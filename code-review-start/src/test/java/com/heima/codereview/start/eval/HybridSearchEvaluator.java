package com.heima.codereview.start.eval;

import com.heima.codereview.rag.model.ReviewRecord;
import com.heima.codereview.rag.retrieval.HybridSearch;
import com.heima.codereview.rag.vector.MilvusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile("eval")
public class HybridSearchEvaluator {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchEvaluator.class);

    private final HybridSearch hybridSearch;
    private final MilvusRepository milvusRepository;

    public HybridSearchEvaluator(HybridSearch hybridSearch, MilvusRepository milvusRepository) {
        this.hybridSearch = hybridSearch;
        this.milvusRepository = milvusRepository;
    }

    public record AblationResult(
            String queryId,
            RetrievalMetrics semanticMetrics,
            RetrievalMetrics bm25Metrics,
            RetrievalMetrics hybridMetrics
    ) {
        public String toPrettyString() {
            return String.format(
                    "Query=%s\n  Semantic: %s\n  BM25:     %s\n  Hybrid:   %s",
                    queryId, semanticMetrics.toPrettyString(), bm25Metrics.toPrettyString(), hybridMetrics.toPrettyString()
            );
        }
    }

    public record EvalSummary(
            double avgRecall,
            double avgPrecision,
            double avgMrr,
            double avgNdcg,
            double avgHitRate,
            List<RetrievalMetrics> perQueryMetrics
    ) {
        public String toPrettyString() {
            return String.format(
                    "RAG Summary: Recall=%.1f%% Precision=%.1f%% MRR=%.3f NDCG=%.3f HitRate=%.1f%% queries=%d",
                    avgRecall * 100, avgPrecision * 100, avgMrr, avgNdcg, avgHitRate * 100, perQueryMetrics.size()
            );
        }
    }

    public AblationResult ablationStudy(RagTestQuery testQuery) {
        int k = 10;

        List<ReviewRecord> semanticOnly;
        try {
            semanticOnly = milvusRepository.search(testQuery.query(), testQuery.projectId(), null, k);
        } catch (Exception e) {
            log.warn("Semantic search failed: {}", e.getMessage());
            semanticOnly = List.of();
        }

        List<ReviewRecord> bm25Only;
        try {
            bm25Only = hybridSearch.search(testQuery.query(), testQuery.projectId(), null, k);
            bm25Only = rerankByKeywordScore(testQuery.query(), bm25Only);
        } catch (Exception e) {
            log.warn("BM25 search failed: {}", e.getMessage());
            bm25Only = List.of();
        }

        List<ReviewRecord> hybrid;
        try {
            hybrid = hybridSearch.search(testQuery.query(), testQuery.projectId(), null, k);
        } catch (Exception e) {
            log.warn("Hybrid search failed: {}", e.getMessage());
            hybrid = List.of();
        }

        return new AblationResult(
                testQuery.queryId(),
                computeMetrics(semanticOnly, testQuery, k),
                computeMetrics(bm25Only, testQuery, k),
                computeMetrics(hybrid, testQuery, k)
        );
    }

    public EvalSummary evaluateAll(List<RagTestQuery> queries) {
        List<RetrievalMetrics> allMetrics = new ArrayList<>();
        for (RagTestQuery q : queries) {
            List<ReviewRecord> results;
            try {
                results = hybridSearch.search(q.query(), q.projectId(), null, 10);
            } catch (Exception e) {
                results = List.of();
            }
            allMetrics.add(computeMetrics(results, q, 10));
        }

        double avgRecall = allMetrics.stream().mapToDouble(RetrievalMetrics::recall).average().orElse(0);
        double avgPrecision = allMetrics.stream().mapToDouble(RetrievalMetrics::precision).average().orElse(0);
        double avgMrr = allMetrics.stream().mapToDouble(RetrievalMetrics::mrr).average().orElse(0);
        double avgNdcg = allMetrics.stream().mapToDouble(RetrievalMetrics::ndcg).average().orElse(0);
        double avgHitRate = allMetrics.stream().filter(m -> m.hitCount() > 0).count() / (double) Math.max(1, allMetrics.size());

        return new EvalSummary(avgRecall, avgPrecision, avgMrr, avgNdcg, avgHitRate, allMetrics);
    }

    RetrievalMetrics computeMetrics(List<ReviewRecord> results, RagTestQuery query, int k) {
        Set<String> retrievedTopK = results.stream().limit(k)
                .map(ReviewRecord::id).collect(Collectors.toSet());

        Map<String, Double> relevant = query.relevantChunkIds();
        int totalRelevant = relevant.size();
        long hits = relevant.keySet().stream().filter(retrievedTopK::contains).count();

        double recall = totalRelevant == 0 ? 1.0 : (double) hits / totalRelevant;
        double precision = k == 0 ? 0 : (double) hits / Math.min(k, Math.max(1, results.size()));

        double mrr = 0.0;
        for (int i = 0; i < results.size(); i++) {
            if (relevant.containsKey(results.get(i).id())) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }

        double ndcg = computeNdcg(results, query, k);

        return new RetrievalMetrics(recall, precision, mrr, ndcg, (int) hits, totalRelevant);
    }

    double computeNdcg(List<ReviewRecord> results, RagTestQuery query, int k) {
        Map<String, Double> relevant = query.relevantChunkIds();
        List<Double> idealScores = relevant.values().stream()
                .sorted(Comparator.reverseOrder()).limit(k).toList();

        double dcg = 0.0;
        double idcg = 0.0;
        for (int i = 0; i < k; i++) {
            double rel = i < results.size()
                    ? relevant.getOrDefault(results.get(i).id(), 0.0) : 0.0;
            dcg += rel / (Math.log(i + 2) / Math.log(2));

            double idealRel = i < idealScores.size() ? idealScores.get(i) : 0.0;
            idcg += idealRel / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 1.0 : dcg / idcg;
    }

    private List<ReviewRecord> rerankByKeywordScore(String query, List<ReviewRecord> results) {
        String normalizedQuery = query.toLowerCase().trim();
        return results.stream()
                .sorted(Comparator
                        .comparingDouble((ReviewRecord r) -> {
                            String content = r.content() == null ? "" : r.content().toLowerCase();
                            return content.contains(normalizedQuery) ? 1.0 : 0.5;
                        })
                        .reversed())
                .toList();
    }
}
