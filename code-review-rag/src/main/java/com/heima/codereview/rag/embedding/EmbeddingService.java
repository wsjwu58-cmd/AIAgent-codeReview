package com.heima.codereview.rag.embedding;

import com.heima.codereview.rag.cache.EmbeddingCache;
import com.heima.codereview.common.monitoring.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int FALLBACK_DIMENSION = 256;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingCache embeddingCache;

    public EmbeddingService(EmbeddingModel embeddingModel, EmbeddingCache embeddingCache) {
        this.embeddingModel = embeddingModel;
        this.embeddingCache = embeddingCache;
        log.info("EmbeddingService 初始化完成, 使用模型: {}", 
                embeddingModel.getClass().getSimpleName());
    }

    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Float> cached = embeddingCache.get(text);
        if (cached != null) {
            MetricsCollector.instance().recordEmbeddingCacheHit(text);
            return cached;
        }

        try {
            float[] vector = embeddingModel.embed(text);
            List<Float> result = new ArrayList<>(vector.length);
            for (float v : vector) {
                result.add(v);
            }
            MetricsCollector.instance().recordEmbeddingGeneration(text, text.length());
            return embeddingCache.putAndGet(text, result);
        } catch (Exception e) {
            log.warn("Embedding 调用失败，改用本地兜底向量。原因={}", e.getMessage());
            List<Float> fallback = fallbackVector(text);
            return embeddingCache.putAndGet(text, fallback);
        }
    }

    public int getDimension() {
        try {
            int dims = embeddingModel.dimensions();
            return dims > 0 ? dims : FALLBACK_DIMENSION;
        } catch (Exception e) {
            log.warn("获取向量维度失败，改用本地兜底维度。原因={}", e.getMessage());
            return FALLBACK_DIMENSION;
        }
    }

    private List<Float> fallbackVector(String text) {
        float[] values = new float[FALLBACK_DIMENSION];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] seed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < values.length; i++) {
                int current = Byte.toUnsignedInt(seed[i % seed.length]);
                values[i] = (current - 128) / 128.0f;
            }
        } catch (Exception e) {
            for (int i = 0; i < values.length; i++) {
                values[i] = 0f;
            }
        }
        List<Float> vector = new ArrayList<>(values.length);
        for (float value : values) {
            vector.add(value);
        }
        return vector;
    }
}
