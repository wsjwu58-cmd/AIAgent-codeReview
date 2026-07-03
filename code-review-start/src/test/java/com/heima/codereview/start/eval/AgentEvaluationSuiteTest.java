package com.heima.codereview.start.eval;

import com.heima.codereview.common.monitoring.MetricsCollector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@SpringBootTest
@ActiveProfiles("eval")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Agent 效果评估套件")
public class AgentEvaluationSuiteTest {

    private static final Logger log = LoggerFactory.getLogger(AgentEvaluationSuiteTest.class);

    @Autowired
    private ToolCallEvaluator toolEval;

    @Autowired
    private HybridSearchEvaluator ragEval;

    @Autowired(required = false)
    private QueryRewriterEvaluator rewriterEval;

    @Autowired(required = false)
    private RerankerEvaluator rerankerEval;

    @Autowired(required = false)
    private LlmJudgeEvaluator qualityEval;

    @Autowired
    private SecurityBenchmark secBench;

    @Autowired
    private LatencyBenchmark latencyBench;

    @Autowired
    private TestDataLoader dataLoader;

    @Autowired(required = false)
    private RagDataSeeder ragDataSeeder;

    @BeforeAll
    static void setup() {
        MetricsCollector.instance().enable();
        MetricsCollector.instance().startSession();
        log.info("========== Agent 效果评估开始 ==========");
    }

    @Test
    @Order(0)
    @DisplayName("Phase 0: 数据加载验证")
    void phase0DataValidation() {
        log.info("--- Phase 0: 数据加载验证 ---");
        log.info("工具调用用例: {} 条", dataLoader.loadTestCasesFromFile(
                "classpath:testdata/tool-calls/tool-call-cases.json", ToolCallTestCase.class).size());
        log.info("RAG 查询用例: {} 条", dataLoader.loadTestCasesFromFile(
                "classpath:testdata/rag/rag-queries.json", RagTestQuery.class).size());
        log.info("安全基准用例: {} 条", dataLoader.loadTestCasesFromFile(
                "classpath:testdata/quality/security-benchmark.json", SecurityTestCase.class).size());
        int qualityFromJson = dataLoader.loadTestCasesFromFile(
                "classpath:testdata/quality/quality-test-cases.json", LlmJudgeEvaluator.QualityTestCase.class).size();
        log.info("质量评估用例(JSON): {} 条", qualityFromJson);
    }

    @Test
    @Order(1)
    @DisplayName("Phase 1: 工具调用评估 (ReAct 循环)")
    void phase1ToolCall() {
        log.info("--- Phase 1: 工具调用评估 (ReAct 循环) ---");
        List<ToolCallTestCase> cases = dataLoader.loadTestCasesFromFile(
                "classpath:testdata/tool-calls/tool-call-cases.json", ToolCallTestCase.class);
        ToolCallEvaluator.ToolCallEvalSummary summary = toolEval.evaluateBatch(cases);
        log.info("{}", summary.toPrettyString());
        for (var r : summary.details()) {
            log.info("  [{}] {} intent={} tools={} paramsOK={} forbiddenOK={} reason={}",
                    r.intentMatch() ? (r.toolNameMatch() ? "PASS" : "TOOL") : "INTENT",
                    r.testId(), r.actualIntent(), r.actualToolsCalled(),
                    r.paramsMatch(), r.forbiddenNotCalled(), r.reasoning());
        }
    }

    @Test
    @Order(2)
    @DisplayName("Phase 2: RAG 检索评估 (含自动播种)")
    void phase2Rag() {
        log.info("--- Phase 2: RAG 检索评估 ---");
        if (ragDataSeeder != null) {
            log.info("Seeding Milvus test data...");
            ragDataSeeder.seedIfNeeded();
        }
        List<RagTestQuery> queries = dataLoader.loadTestCasesFromFile(
                "classpath:testdata/rag/rag-queries.json", RagTestQuery.class);
        var summary = ragEval.evaluateAll(queries);
        log.info("{}", summary.toPrettyString());
    }

    @Test
    @Order(3)
    @DisplayName("Phase 3: LLM-as-Judge 质量评估")
    void phase3Quality() {
        log.info("--- Phase 3: LLM-as-Judge 质量评估 ---");
        if (qualityEval == null) {
            log.warn("LlmJudgeEvaluator not available, skipping quality evaluation");
            return;
        }

        List<LlmJudgeEvaluator.QualityTestCase> qualityCases = dataLoader.loadTestCasesFromFile(
                "classpath:testdata/quality/quality-test-cases.json", LlmJudgeEvaluator.QualityTestCase.class);

        if (qualityCases.isEmpty()) {
            log.info("No quality test cases from JSON, using built-in defaults");
            qualityCases = qualityEval.buildDefaultTestCases();
        }

        log.info("Evaluating {} quality test cases...", qualityCases.size());
        LlmJudgeEvaluator.QualityEvalSummary summary = qualityEval.evaluateBatch(qualityCases);
        log.info("{}", summary.toPrettyString());
        for (JudgeResult r : summary.details()) {
            log.info("  overall={} correctness={} completeness={} relevance={} safety={} fluency={} helpfulness={} comment={}",
                    r.overall(), r.correctness(), r.completeness(), r.relevance(),
                    r.safety(), r.fluency(), r.helpfulness(), r.comment());
        }
    }

    @Test
    @Order(4)
    @DisplayName("Phase 4: 安全漏洞基准")
    void phase4Security() {
        log.info("--- Phase 4: 安全漏洞基准 ---");
        List<SecurityTestCase> cases = dataLoader.loadTestCasesFromFile(
                "classpath:testdata/quality/security-benchmark.json", SecurityTestCase.class);
        var result = secBench.runBenchmark(cases);
        log.info("{}", result.toPrettyString());
        log.info("  TP={} FP={} TN={} FN={}", result.truePositives(), result.falsePositives(), result.trueNegatives(), result.falseNegatives());
    }

    @Test
    @Order(5)
    @DisplayName("Phase 5: 端到端延迟基准")
    void phase5Latency() {
        log.info("--- Phase 5: 端到端延迟基准 ---");
        var queries = List.of("检查这段代码的安全性", "优化这段代码性能", "这段 SQL 是否有注入风险");
        var report = latencyBench.benchmarkLatency(queries, 0, 2);
        log.info("{}", report.toPrettyString());
    }

    @Test
    @Order(6)
    @DisplayName("Phase 6: Token 消耗摘要")
    void phase6Token() {
        log.info("--- Phase 6: Token 消耗摘要 ---");
        var summary = MetricsCollector.instance().summarize();
        log.info("\n{}", summary.toPrettyString());
    }
}
