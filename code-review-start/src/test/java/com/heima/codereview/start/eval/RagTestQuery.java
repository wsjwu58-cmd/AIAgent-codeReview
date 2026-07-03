package com.heima.codereview.start.eval;

import java.util.List;
import java.util.Map;

public record RagTestQuery(
    String queryId,
    String query,
    String projectId,
    Map<String, Double> relevantChunkIds,
    List<String> expectedTopics
) {
    public RagTestQuery {
        relevantChunkIds = relevantChunkIds == null ? Map.of() : Map.copyOf(relevantChunkIds);
        expectedTopics = expectedTopics == null ? List.of() : List.copyOf(expectedTopics);
    }
}
