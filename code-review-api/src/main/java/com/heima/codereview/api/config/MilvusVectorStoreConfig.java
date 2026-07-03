package com.heima.codereview.api.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.ReleaseCollectionParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = true)
public class MilvusVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStoreConfig.class);
    private static final String DIMENSION_KEY = "dim";

    @Bean
    @Primary
    public MilvusVectorStore vectorStore(MilvusServiceClient milvusClient,
                                         EmbeddingModel embeddingModel,
                                         MilvusVectorStoreProperties properties,
                                         BatchingStrategy batchingStrategy) throws Exception {
        int desiredDimension = resolveEmbeddingDimension(embeddingModel, properties);
        reconcileCollectionSchema(milvusClient, properties, desiredDimension);

        MilvusVectorStore.Builder builder = MilvusVectorStore.builder(milvusClient, embeddingModel)
                .initializeSchema(properties.isInitializeSchema())
                .databaseName(properties.getDatabaseName())
                .collectionName(properties.getCollectionName())
                .embeddingDimension(desiredDimension)
                .indexType(IndexType.valueOf(properties.getIndexType().name()))
                .metricType(MetricType.valueOf(properties.getMetricType().name()))
                .indexParameters(properties.getIndexParameters())
                .iDFieldName(properties.getIdFieldName())
                .autoId(properties.isAutoId())
                .contentFieldName(properties.getContentFieldName())
                .metadataFieldName(properties.getMetadataFieldName())
                .embeddingFieldName(properties.getEmbeddingFieldName());
        builder.batchingStrategy(batchingStrategy);

        MilvusVectorStore vectorStore = builder.build();
        vectorStore.afterPropertiesSet();
        return vectorStore;
    }

    private int resolveEmbeddingDimension(EmbeddingModel embeddingModel, MilvusVectorStoreProperties properties) {
        try {
            int detectedDimension = embeddingModel.dimensions();
            if (detectedDimension > 0) {
                log.info("Detected embedding dimension from current model: {}", detectedDimension);
                return detectedDimension;
            }
        } catch (Exception e) {
            log.warn("Failed to detect embedding dimension from model, fallback to configured dimension. reason={}",
                    e.getMessage());
        }

        if (properties.getEmbeddingDimension() > 0) {
            log.info("Use configured embedding dimension as fallback: {}", properties.getEmbeddingDimension());
            return properties.getEmbeddingDimension();
        }
        return MilvusVectorStore.OPENAI_EMBEDDING_DIMENSION_SIZE;
    }

    private void reconcileCollectionSchema(MilvusServiceClient milvusClient,
                                           MilvusVectorStoreProperties properties,
                                           int desiredDimension) {
        if (!collectionExists(milvusClient, properties)) {
            return;
        }

        int currentDimension = getCurrentCollectionDimension(milvusClient, properties);
        if (currentDimension <= 0 || currentDimension == desiredDimension) {
            return;
        }

        String collectionLabel = properties.getDatabaseName() + "." + properties.getCollectionName();
        if (!properties.isInitializeSchema()) {
            throw new IllegalStateException("Milvus collection " + collectionLabel
                    + " embedding dimension mismatch. current=" + currentDimension
                    + ", expected=" + desiredDimension
                    + ". Please enable initialize-schema or recreate the collection manually.");
        }

        log.warn("Milvus collection dimension mismatch detected. collection={}, currentDimension={}, desiredDimension={}. Recreating collection.",
                collectionLabel, currentDimension, desiredDimension);
        releaseCollection(milvusClient, properties);
        dropCollection(milvusClient, properties);
    }

    private boolean collectionExists(MilvusServiceClient milvusClient, MilvusVectorStoreProperties properties) {
        R<Boolean> response = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .build());
        return Boolean.TRUE.equals(response.getData());
    }

    private int getCurrentCollectionDimension(MilvusServiceClient milvusClient, MilvusVectorStoreProperties properties) {
        R<DescribeCollectionResponse> response = milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .build());
        DescribeCollectionResponse description = response.getData();
        if (description == null || !description.hasSchema()) {
            return MilvusVectorStore.INVALID_EMBEDDING_DIMENSION;
        }

        String embeddingFieldName = StringUtils.hasText(properties.getEmbeddingFieldName())
                ? properties.getEmbeddingFieldName()
                : MilvusVectorStore.EMBEDDING_FIELD_NAME;
        for (FieldSchema fieldSchema : description.getSchema().getFieldsList()) {
            if (!embeddingFieldName.equals(fieldSchema.getName())) {
                continue;
            }
            for (KeyValuePair typeParam : fieldSchema.getTypeParamsList()) {
                if (!DIMENSION_KEY.equalsIgnoreCase(typeParam.getKey())) {
                    continue;
                }
                try {
                    return Integer.parseInt(typeParam.getValue());
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse Milvus embedding dimension. collection={}, rawValue={}",
                            properties.getCollectionName(), typeParam.getValue());
                    return MilvusVectorStore.INVALID_EMBEDDING_DIMENSION;
                }
            }
        }
        return MilvusVectorStore.INVALID_EMBEDDING_DIMENSION;
    }

    private void releaseCollection(MilvusServiceClient milvusClient, MilvusVectorStoreProperties properties) {
        try {
            milvusClient.releaseCollection(ReleaseCollectionParam.newBuilder()
                    .withDatabaseName(properties.getDatabaseName())
                    .withCollectionName(properties.getCollectionName())
                    .build());
        } catch (Exception e) {
            log.warn("Release Milvus collection failed before recreation. collection={}, reason={}",
                    properties.getCollectionName(), e.getMessage());
        }
    }

    private void dropCollection(MilvusServiceClient milvusClient, MilvusVectorStoreProperties properties) {
        milvusClient.dropCollection(DropCollectionParam.newBuilder()
                .withDatabaseName(properties.getDatabaseName())
                .withCollectionName(properties.getCollectionName())
                .build());
    }
}
