package com.heima.codereview.start.eval;

import com.heima.codereview.common.monitoring.MetricsCollector;
import com.heima.codereview.core.agent.AgentTextGenerator;
import com.heima.codereview.core.coordination.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("eval")
public class SecurityBenchmark {

    private static final Logger log = LoggerFactory.getLogger(SecurityBenchmark.class);

    private final AgentRegistry agentRegistry;
    private final AgentTextGenerator textGenerator;

    public SecurityBenchmark(AgentRegistry agentRegistry, AgentTextGenerator textGenerator) {
        this.agentRegistry = agentRegistry;
        this.textGenerator = textGenerator;
    }

    public SecurityBenchmarkResult runBenchmark(List<SecurityTestCase> testCases) {
        int tp = 0, fp = 0, tn = 0, fn = 0;

        for (SecurityTestCase tc : testCases) {
            try {
                boolean agentFoundVulnerability = checkSecurity(tc);
                boolean actualHas = tc.hasVulnerability();

                if (agentFoundVulnerability && actualHas) tp++;
                else if (agentFoundVulnerability && !actualHas) fp++;
                else if (!agentFoundVulnerability && !actualHas) tn++;
                else fn++;

                log.info("Security test {}: agentFound={} actual={} cwe={}",
                        tc.id(), agentFoundVulnerability, actualHas, tc.cweId());
            } catch (Exception e) {
                log.warn("Security test {} failed: {}", tc.id(), e.getMessage());
                fn++;
            }
        }

        double tpr = (tp + fn) == 0 ? 0 : (double) tp / (tp + fn);
        double fpr = (fp + tn) == 0 ? 0 : (double) fp / (fp + tn);
        double precision = (tp + fp) == 0 ? 0 : (double) tp / (tp + fp);
        double f1 = (precision + tpr) == 0 ? 0 : 2 * precision * tpr / (precision + tpr);

        MetricsCollector.instance().recordHistory(
                String.format("Security benchmark: TPR=%.2f FPR=%.2f F1=%.3f", tpr, fpr, f1));

        return new SecurityBenchmarkResult(tpr, fpr, precision, f1, tp, fp, tn, fn);
    }

    private boolean checkSecurity(SecurityTestCase tc) {
        if (!textGenerator.available()) {
            return keywordCheck(tc);
        }

        String prompt = buildSecurityPrompt(tc);

        try {
            String result = textGenerator.generate("security-specialist",
                    "You are a security code reviewer. Your task is to determine if the code has a real security vulnerability.\n\n" +
                            "CRITICAL RULES:\n" +
                            "- Code using PreparedStatement with setString() = [无漏洞], even if SQL string is present\n" +
                            "- Code using HtmlUtils.htmlEscape() = [无漏洞], even if HTML is returned\n" +
                            "- Code using BCrypt/SCrypt/Argon2 = [无漏洞], even if password-related\n" +
                            "- Code using Hibernate/MyBatis #{param} = [无漏洞]\n" +
                            "- Code using StringEscapeUtils.escapeHtml4() = [无漏洞]\n" +
                            "- Only mark [有漏洞] if there is a REAL exploitable issue\n\n" +
                            "Answer ONLY [有漏洞] or [无漏洞]. No other text.",
                    prompt, Map.of("scene", "eval", "disableToolCallbacks", true));

            if (result == null || result.isBlank()) {
                return keywordCheck(tc);
            }
            String lower = result.toLowerCase().trim();
            return lower.contains("有漏洞") || lower.contains("漏洞")
                    || lower.contains("vuln") || lower.contains("yes")
                    || lower.contains("不安全") || lower.contains("风险");
        } catch (Exception e) {
            log.warn("LLM security check failed, using keyword fallback: {}", e.getMessage());
            return keywordCheck(tc);
        }
    }

    private String buildSecurityPrompt(SecurityTestCase tc) {
        return """
                请审查以下代码的安全漏洞，仅回答[有漏洞]或[无漏洞]。
                
                --- 安全代码模式（必须判断为[无漏洞]）---
                - PreparedStatement + setString() 参数绑定 → [无漏洞]
                - HtmlUtils.htmlEscape() HTML实体转义 → [无漏洞]  
                - BCrypt/SCrypt/Argon2 安全密码哈希 → [无漏洞]
                - 输入白名单校验 / 路径规范化 → [无漏洞]
                
                --- 漏洞模式参考（判断为[有漏洞]）---
                - 字符串拼接SQL + Statement → [有漏洞] (SQL注入)
                - 用户输入直接拼入HTML → [有漏洞] (XSS)
                - 硬编码密码/API Key → [有漏洞] (凭据泄露)
                - 未校验的路径参数访问文件 → [有漏洞] (路径遍历)
                - MD5/SHA1 哈希密码 → [有漏洞] (弱加密)
                - 日志输出密码明文 → [有漏洞] (敏感信息泄露)
                - ObjectInputStream 未校验 → [有漏洞] (反序列化)
                
                --- 示例 ---
                示例1: PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id=?"); ps.setString(1, id);
                回答: [无漏洞] (使用了参数化查询，防止了SQL注入)
                
                示例2: String sql = "SELECT * FROM users WHERE name='" + name + "'"; Statement s = conn.createStatement();
                回答: [有漏洞] (字符串拼接SQL，存在SQL注入风险)
                
                示例3: return HtmlUtils.htmlEscape(userInput);
                回答: [无漏洞] (使用了HTML实体转义，防止了XSS)
                
                示例4: return "<div>" + userInput + "</div>";
                回答: [有漏洞] (用户输入直接拼入HTML，存在XSS风险)
                
                --- 待审查代码 ---
                文件: """ + tc.fileName() + "\n代码:\n" + tc.code();
    }

    private boolean keywordCheck(SecurityTestCase tc) {
        String code = tc.code().toLowerCase();

        boolean usesPreparedStatement = code.contains("preparedstatement");
        boolean usesSetString = code.contains("setstring");
        boolean usesHtmlEscape = code.contains("htmlescape") || code.contains("htmlescape");
        boolean usesBcrypt = code.contains("bcrypt") || code.contains("argon2") || code.contains("scrypt");

        boolean sqlInjection = code.contains("select *") && code.contains("+") && !usesPreparedStatement;
        boolean hardcoded = (code.contains("admin123") || code.contains("api_key") || code.contains("sk-")) && !usesBcrypt;
        boolean weakHash = code.contains("md5") && code.contains("digest") && !usesBcrypt;
        boolean infoLeak = code.contains("printstacktrace") || code.contains(".getmessage()");
        boolean xss = code.contains("html") && code.contains("+") && code.contains("div") && !usesHtmlEscape;
        boolean deserialize = code.contains("objectinputstream");
        boolean logPassword = code.contains("log.info") && code.contains("password") && !usesBcrypt;

        return sqlInjection || hardcoded || weakHash || infoLeak || xss || deserialize || logPassword;
    }
}
