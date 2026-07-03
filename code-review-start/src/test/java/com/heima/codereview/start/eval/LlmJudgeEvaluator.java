package com.heima.codereview.start.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heima.codereview.core.agent.AgentTextGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Profile("eval")
public class LlmJudgeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeEvaluator.class);

    private final AgentTextGenerator productionModel;
    private final JudgeTextEvaluator judgeModel;
    private final ObjectMapper objectMapper;
    private final TestDataLoader dataLoader;

    public LlmJudgeEvaluator(AgentTextGenerator productionModel,
                             JudgeTextEvaluator judgeModel,
                             ObjectMapper objectMapper,
                             TestDataLoader dataLoader) {
        this.productionModel = productionModel;
        this.judgeModel = judgeModel;
        this.objectMapper = objectMapper;
        this.dataLoader = dataLoader;
    }

    public record QualityTestCase(
            String id,
            String userQuery,
            String agentId,
            String agentResponse,
            String expectedKeyPoints
    ) {}

    public record QualityEvalSummary(
            double avgOverall,
            double avgCorrectness,
            double avgCompleteness,
            double avgRelevance,
            double avgSafety,
            double avgFluency,
            double avgHelpfulness,
            double passRate,
            List<JudgeResult> details
    ) {
        public String toPrettyString() {
            return String.format(
                    "Quality Summary: overall=%.1f correctness=%.1f completeness=%.1f relevance=%.1f safety=%.1f fluency=%.1f helpfulness=%.1f passRate=%.1f%% total=%d",
                    avgOverall, avgCorrectness, avgCompleteness, avgRelevance, avgSafety, avgFluency, avgHelpfulness,
                    passRate * 100, details.size()
            );
        }
    }

    public QualityEvalSummary evaluateBatch(List<QualityTestCase> cases) {
        List<JudgeResult> results = new ArrayList<>();
        for (QualityTestCase tc : cases) {
            JudgeResult result = evaluateWithAgentGeneration(tc);
            results.add(result);
        }

        int n = Math.max(1, results.size());
        double sumOverall = 0, sumCorrectness = 0, sumCompleteness = 0,
               sumRelevance = 0, sumSafety = 0, sumFluency = 0, sumHelpfulness = 0;
        int passCount = 0;

        for (JudgeResult r : results) {
            sumOverall += r.overall();
            sumCorrectness += r.correctness();
            sumCompleteness += r.completeness();
            sumRelevance += r.relevance();
            sumSafety += r.safety();
            sumFluency += r.fluency();
            sumHelpfulness += r.helpfulness();
            if (r.passed()) passCount++;
        }

        return new QualityEvalSummary(
                sumOverall / n, sumCorrectness / n, sumCompleteness / n,
                sumRelevance / n, sumSafety / n, sumFluency / n, sumHelpfulness / n,
                (double) passCount / n, results
        );
    }

    private JudgeResult evaluateWithAgentGeneration(QualityTestCase tc) {
        String agentResponse = generateAgentResponse(tc);
        return evaluate(agentResponse, tc.userQuery(), tc.agentId(), tc.expectedKeyPoints());
    }

    private String generateAgentResponse(QualityTestCase tc) {
        if (!productionModel.available()) {
            log.warn("Production LLM unavailable, cannot generate agent response for {}", tc.id());
            return "";
        }
        try {
            String prompt = buildAgentPrompt(tc);
            return productionModel.generate(tc.agentId(),
                    buildAgentSystemPrompt(tc.agentId()),
                    prompt,
                    Map.of("scene", "eval-quality", "disableToolCallbacks", true));
        } catch (Exception e) {
            log.warn("Agent response generation failed for {}: {}", tc.id(), e.getMessage());
            return "";
        }
    }

    private String buildAgentPrompt(QualityTestCase tc) {
        return "请回答以下问题，给出专业、准确、完整的答案：\n\n" + tc.userQuery();
    }

    private String buildAgentSystemPrompt(String agentId) {
        return switch (agentId) {
            case "security-specialist" ->
                    "You are a senior security engineer. Provide accurate, actionable security analysis. " +
                    "Identify vulnerabilities and suggest concrete fixes. Answer in Simplified Chinese.";
            case "general-coding" ->
                    "You are a senior software engineer. Provide clear, practical coding guidance. " +
                    "Include code examples and best practices. Answer in Simplified Chinese.";
            default ->
                    "You are a knowledgeable programming assistant. Provide helpful, accurate answers. " +
                    "Answer in Simplified Chinese.";
        };
    }

    public JudgeResult evaluate(String agentResponse, String userQuery, String agentId, String expectedKeyPoints) {
        if (!judgeModel.available()) {
            log.warn("Judge model not available, using heuristic fallback");
            return heuristicJudge(agentResponse, expectedKeyPoints);
        }

        String promptTemplate = dataLoader.loadPromptTemplate("classpath:testdata/prompts/judge-prompt.md");
        if (promptTemplate.isEmpty()) {
            promptTemplate = buildDefaultJudgePrompt();
        }

        String userPrompt = promptTemplate
                .replace("${user_query}", userQuery)
                .replace("${agent_type}", agentId)
                .replace("${agent_response}", truncate(agentResponse, 4000))
                .replace("${expected_key_points}", truncate(expectedKeyPoints, 2000));

        try {
            String judgeOutput = judgeModel.evaluate(
                    "You are a response quality evaluator. Score the agent response accurately and objectively.",
                    userPrompt,
                    Map.of("scene", "eval-judge"));

            if (judgeOutput == null || judgeOutput.isBlank()) {
                return heuristicJudge(agentResponse, expectedKeyPoints);
            }
            return parseJudgeResponse(judgeOutput);
        } catch (Exception e) {
            log.warn("LLM Judge evaluation failed: {}", e.getMessage());
            return heuristicJudge(agentResponse, expectedKeyPoints);
        }
    }

    public List<QualityTestCase> buildDefaultTestCases() {
        return List.of(
                new QualityTestCase("QUAL-001",
                        "这段 SQL 是否有注入风险：String sql = \"SELECT * FROM users WHERE id=\" + userId; Statement stmt = conn.createStatement(); stmt.executeQuery(sql);",
                        "security-specialist", "",
                        "SQL注入,字符串拼接,参数化查询,PreparedStatement"),
                new QualityTestCase("QUAL-002",
                        "Java 中如何安全地存储密码",
                        "general-coding", "",
                        "BCrypt,哈希,加盐,不能明文存储,不能用MD5"),
                new QualityTestCase("QUAL-003",
                        "这段文件下载代码有什么安全问题：File file = new File(\"/uploads/\" + filename); Files.copy(file.toPath(), resp.getOutputStream());",
                        "security-specialist", "",
                        "路径遍历,path traversal,文件名校验"),
                new QualityTestCase("QUAL-004",
                        "如何避免日志泄露敏感信息",
                        "general-coding", "",
                        "脱敏,password,mask,日志脱敏,不记录密码"),
                new QualityTestCase("QUAL-005",
                        "这个异常处理有什么问题：catch (Exception e) { response.getWriter().write(e.getMessage()); e.printStackTrace(); }",
                        "security-specialist", "",
                        "信息泄露,getMessage,printStackTrace,内部错误"),
                new QualityTestCase("QUAL-006",
                        "如何使用 PreparedStatement 防止 SQL 注入",
                        "general-coding", "",
                        "PreparedStatement,参数化查询,setString,占位符"),
                new QualityTestCase("QUAL-007",
                        "这段代码的密码存储是否安全：MessageDigest md = MessageDigest.getInstance(\"MD5\"); byte[] hash = md.digest(password.getBytes());",
                        "security-specialist", "",
                        "MD5不安全,弱哈希,BCrypt,加盐"),
                new QualityTestCase("QUAL-008",
                        "XSS 攻击是什么，如何在 Java Web 应用中防止",
                        "general-coding", "",
                        "XSS,HTML转义,HtmlUtils.htmlEscape,Content-Security-Policy"),
                new QualityTestCase("QUAL-009",
                        "这段反序列化代码是否有风险：ObjectInputStream ois = new ObjectInputStream(new FileInputStream(\"data.dat\")); Object obj = ois.readObject();",
                        "security-specialist", "",
                        "反序列化漏洞,ObjectInputStream,类型校验"),
                new QualityTestCase("QUAL-010",
                        "Java项目中 API Key 应该如何管理",
                        "general-coding", "",
                        "环境变量,不硬编码,配置管理,密钥管理")
        );
    }

    private JudgeResult parseJudgeResponse(String response) {
        try {
            String json = extractJson(response);
            JsonNode node = objectMapper.readTree(json);
            return new JudgeResult(
                    node.has("correctness") ? node.get("correctness").asInt(0) : 3,
                    node.has("completeness") ? node.get("completeness").asInt(0) : 3,
                    node.has("relevance") ? node.get("relevance").asInt(0) : 3,
                    node.has("safety") ? node.get("safety").asInt(0) : 3,
                    node.has("fluency") ? node.get("fluency").asInt(0) : 3,
                    node.has("helpfulness") ? node.get("helpfulness").asInt(0) : 3,
                    node.has("overall") ? node.get("overall").asInt(0) : 3,
                    node.has("comment") ? node.get("comment").asText("") : ""
            );
        } catch (Exception e) {
            log.warn("Failed to parse judge response: {}", e.getMessage());
            return JudgeResult.fallback();
        }
    }

    private JudgeResult heuristicJudge(String agentResponse, String expectedKeyPoints) {
        int score = 3;
        if (agentResponse != null && !agentResponse.isBlank()) {
            if (agentResponse.length() > 100) score = 4;
            if (expectedKeyPoints != null && !expectedKeyPoints.isBlank()) {
                String[] points = expectedKeyPoints.split("[,\n，、；]");
                int matched = 0;
                for (String point : points) {
                    String trimmed = point.trim();
                    if (!trimmed.isEmpty() && agentResponse.toLowerCase().contains(trimmed.toLowerCase())) {
                        matched++;
                    }
                }
                if (matched >= points.length * 0.5) score = 5;
                else if (matched >= points.length * 0.25) score = 4;
                else score = 3;
            }
        }
        return new JudgeResult(score, score, score, 4, 4, score, score, "heuristic-fallback");
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...(truncated)";
    }

    private String buildDefaultJudgePrompt() {
        return """
                你是一个 Agent 回复质量评估专家。请根据以下维度对 Agent 回复进行 1-5 打分（5=优秀）：

                【用户问题】
                ${user_query}

                【Agent 类型】
                ${agent_type}

                【Agent 回复】
                ${agent_response}

                【期望要点（如有）】
                ${expected_key_points}

                请按以下维度评分（1-5 分）：
                - correctness: 回复内容是否事实正确、无幻觉
                - completeness: 是否覆盖了问题的所有关键方面
                - relevance: 回复是否紧扣用户问题，无无关信息
                - safety: 是否避免了不安全建议（SQL拼接、硬编码密钥等）
                - fluency: 语言是否流畅、逻辑清晰
                - helpfulness: 对用户是否有实际帮助，是否可落地执行
                - overall: 整体质量评分

                返回 JSON 格式：
                {"correctness": 4, "completeness": 5, "relevance": 4, "safety": 5, "fluency": 4, "helpfulness": 5, "overall": 4, "comment": "..."}
                """;
    }
}
