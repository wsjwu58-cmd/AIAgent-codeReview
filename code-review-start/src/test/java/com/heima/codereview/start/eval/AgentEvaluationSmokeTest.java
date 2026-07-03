package com.heima.codereview.start.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("评估基础冒烟测试（无需 Spring 上下文）")
class AgentEvaluationSmokeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("测试数据文件可加载")
    void testDataFilesLoadable() {
        assertTrue(getClass().getResourceAsStream("/testdata/tool-calls/tool-call-cases.json") != null,
                "tool-call-cases.json should exist");
        assertTrue(getClass().getResourceAsStream("/testdata/rag/rag-queries.json") != null,
                "rag-queries.json should exist");
        assertTrue(getClass().getResourceAsStream("/testdata/quality/security-benchmark.json") != null,
                "security-benchmark.json should exist");
        assertTrue(getClass().getResourceAsStream("/testdata/prompts/judge-prompt.md") != null,
                "judge-prompt.md should exist");
    }

    @Test
    @DisplayName("工具调用测试用例 JSON 格式正确")
    void toolCallCasesParseable() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/tool-calls/tool-call-cases.json")) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            List<?> cases = objectMapper.readValue(json, List.class);
            assertFalse(cases.isEmpty(), "Should have test cases");
        }
    }

    @Test
    @DisplayName("安全基准测试用例 JSON 格式正确")
    void securityBenchmarkParseable() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/testdata/quality/security-benchmark.json")) {
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            List<?> cases = objectMapper.readValue(json, List.class);
            assertFalse(cases.isEmpty(), "Should have test cases");
        }
    }

    @Test
    @DisplayName("文本相似度计算")
    void textSimilarity() {
        double sim = AgentSnapshotTest.computeTextSimilarity(
                "这段代码存在 SQL 注入漏洞，建议使用 PreparedStatement",
                "这段代码存在SQL注入漏洞,建议使用PreparedStatement"
        );
        assertTrue(sim > 0.5, "Similar texts should have high similarity, got: " + sim);
    }

    @Test
    @DisplayName("文本标准化")
    void textNormalization() {
        String result = AgentSnapshotTest.normalizeOutput("Hello  World\nTest");
        assertFalse(result.contains("\n"), "Should remove newlines");
        assertFalse(result.contains("  "), "Should collapse whitespace");
    }

    @Test
    @DisplayName("Token 估算")
    void tokenEstimation() {
        long tokens = com.heima.codereview.common.monitoring.MetricsCollector.estimateTokens(300);
        assertTrue(tokens >= 50 && tokens <= 150, "Token estimate for 300 chars should be ~100");
    }
}
