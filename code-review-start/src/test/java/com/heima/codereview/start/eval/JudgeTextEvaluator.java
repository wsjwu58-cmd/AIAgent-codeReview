package com.heima.codereview.start.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@Profile("eval")
public class JudgeTextEvaluator {

    private static final Logger log = LoggerFactory.getLogger(JudgeTextEvaluator.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public JudgeTextEvaluator(
            @Value("${spring.ai.judge.api-key:}") String apiKey,
            @Value("${spring.ai.judge.base-url:https://api.siliconflow.cn}") String baseUrl,
            @Value("${spring.ai.judge.model:deepseek-ai/DeepSeek-V3}") String model,
            @Value("${spring.ai.judge.temperature:0.1}") double temperature,
            @Value("${spring.ai.judge.max-tokens:1024}") int maxTokens,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .build();
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String evaluate(String systemPrompt, String userPrompt, Map<String, Object> context) {
        if (!available()) {
            log.warn("Judge LLM not available, apiKey is blank");
            return "";
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", maxTokens);

            ArrayNode messages = objectMapper.createArrayNode();
            ObjectNode sysMsg = objectMapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            requestBody.set("messages", messages);

            String scene = context != null ? String.valueOf(context.getOrDefault("scene", "eval")) : "eval";
            log.info("Judge LLM evaluating. scene={}, model={}, systemLen={}, userLen={}",
                    scene, model, systemPrompt.length(), userPrompt.length());

            String response = restClient.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody.toString())
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                log.warn("Judge LLM returned empty response");
                return "";
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode message = choices.get(0).path("message");
                String content = message.path("content").asText("");
                log.info("Judge LLM response received. scene={}, contentLen={}", scene, content.length());
                return content;
            }

            log.warn("Judge LLM response missing choices array");
            return "";
        } catch (Exception e) {
            log.warn("Judge LLM evaluation failed: {}", e.getMessage());
            return "";
        }
    }
}
