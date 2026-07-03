package com.heima.codereview.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusServiceClientConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = true)
public class MilvusConnectionConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConnectionConfig.class);

    @Bean
    public MilvusServiceClientConnectionDetails milvusServiceClientConnectionDetails(
            @Value("${spring.ai.vectorstore.milvus.client.host:localhost}") String rawHost,
            @Value("${spring.ai.vectorstore.milvus.client.port:19530}") int configuredPort) {
        ParsedMilvusEndpoint endpoint = parse(rawHost, configuredPort);
        log.info("Milvus连接配置已生效。host={}, port={}", endpoint.host(), endpoint.port());
        return new MilvusServiceClientConnectionDetails() {
            @Override
            public String getHost() {
                return endpoint.host();
            }

            @Override
            public int getPort() {
                return endpoint.port();
            }
        };
    }

    private ParsedMilvusEndpoint parse(String rawHost, int configuredPort) {
        if (rawHost == null || rawHost.isBlank()) {
            return new ParsedMilvusEndpoint("localhost", configuredPort);
        }

        String trimmed = rawHost.trim();
        if (!trimmed.contains("://")) {
            return new ParsedMilvusEndpoint(trimmed, configuredPort);
        }

        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : configuredPort;
            if (host != null && !host.isBlank()) {
                return new ParsedMilvusEndpoint(host, port);
            }
        } catch (Exception e) {
            log.warn("解析Milvus地址失败，将按原始host继续连接。rawHost={}, reason={}", trimmed, e.getMessage());
        }
        return new ParsedMilvusEndpoint(trimmed, configuredPort);
    }

    private record ParsedMilvusEndpoint(String host, int port) {
    }
}
