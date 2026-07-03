package com.heima.codereview.start.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("eval")
public class TestDataLoader {

    private static final Logger log = LoggerFactory.getLogger(TestDataLoader.class);
    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resolver;

    public TestDataLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    /**
     * 从 classpath:testdata/ 通配路径加载测试用例
     */
    public <T> List<T> loadTestCases(String resourcePattern, Class<T> type) {
        List<T> cases = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources(resourcePattern);
            log.info("Found {} resources matching pattern: {}", resources.length, resourcePattern);
            for (Resource resource : resources) {
                List<T> loaded = loadJsonFile(resource, type);
                cases.addAll(loaded);
                log.info("Loaded {} cases from {}", loaded.size(), resource.getFilename());
            }
        } catch (IOException e) {
            log.warn("Failed to load test cases from pattern: {}", resourcePattern, e);
        }
        return cases;
    }

    /**
     * 从单个 JSON 文件加载测试用例
     */
    public <T> List<T> loadTestCasesFromFile(String resourcePath, Class<T> type) {
        try {
            Resource[] resources = resolver.getResources(resourcePath);
            if (resources.length == 0) {
                log.warn("No resource found: {}", resourcePath);
                return List.of();
            }
            List<T> all = new ArrayList<>();
            for (Resource r : resources) {
                all.addAll(loadJsonFile(r, type));
            }
            return all;
        } catch (IOException e) {
            log.warn("Failed to load test cases: {}", resourcePath, e);
            return List.of();
        }
    }

    public String loadPromptTemplate(String resourcePath) {
        try {
            Resource resource = resolver.getResource(resourcePath);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Failed to load prompt template: {}", resourcePath, e);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> loadJsonFile(Resource resource, Class<T> type) throws IOException {
        try (InputStream is = resource.getInputStream()) {
            if (is == null) {
                return List.of();
            }
            byte[] bytes = is.readAllBytes();
            if (bytes.length == 0) {
                return List.of();
            }
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            if (content.startsWith("[")) {
                return objectMapper.readValue(content,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, type));
            }
            T item = objectMapper.readValue(content, type);
            return List.of(item);
        }
    }
}
