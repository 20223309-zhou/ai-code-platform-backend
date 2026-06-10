package com.ai.codeplatform.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant 向量数据库配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "qdrant")
@Slf4j
public class QdrantConfig {
    
    private String host;
    private Integer port;
    private String collectionName;
    private Integer vectorSize;
    private String distanceType;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .build();
    }

    /**
     * 应用启动时自动创建集合（如果不存在）
     */
    @PostConstruct
    public void initCollection() {
        try {
            // 创建 Qdrant 客户端
            QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, port, false)
                    .build();
            QdrantClient client = new QdrantClient(grpcClient);
            // 检查集合是否存在
            boolean exists = client.collectionExistsAsync(collectionName).get();

            if (!exists) {
                log.info("创建 Qdrant 集合: {}", collectionName);

                // 将字符串转换为 Qdrant 的 Distance 枚举
                Collections.Distance distance = parseDistance(distanceType);

                // 创建集合
                client.createCollectionAsync(
                        collectionName,
                        Collections.VectorParams.newBuilder()
                                .setSize(vectorSize)
                                .setDistance(distance)
                                .build()
                ).get();

                log.info("Qdrant 集合创建成功: {}", collectionName);
            } else {
                log.info("Qdrant 集合已存在: {}", collectionName);
            }

            client.close();

        } catch (Exception e) {
            log.error("初始化 Qdrant 集合失败", e);
            throw new RuntimeException("初始化 Qdrant 集合失败", e);
        }
    }

    /**
     * 解析距离类型字符串为 Qdrant Distance 枚举
     */
    private Collections.Distance parseDistance(String distanceType) {
        return switch (distanceType.toLowerCase()) {
            case "cosine" -> Collections.Distance.Cosine;
            case "euclidean" -> Collections.Distance.Euclid;
            case "dot" -> Collections.Distance.Dot;
            case "manhattan" -> Collections.Distance.Manhattan;
            default -> {
                log.warn("⚠未知的距离类型: {}，使用默认的 Cosine", distanceType);
                yield Collections.Distance.Cosine;
            }
        };
    }
}
