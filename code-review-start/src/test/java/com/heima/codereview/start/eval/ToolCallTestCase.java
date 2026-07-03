package com.heima.codereview.start.eval;

import java.util.List;
import java.util.Map;

public record ToolCallTestCase(
    String id,
    String userQuery,
    String expectedIntent,
    List<ExpectedToolCall> expectedTools,
    List<String> forbiddenTools
) {}

record ExpectedToolCall(
    String toolName,
    Map<String, Object> expectedParams,
    boolean paramsAreSubset
) {}
