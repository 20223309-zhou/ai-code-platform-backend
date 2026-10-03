package com.ai.codeplatform.rag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 应用启动时自动创建 Qdrant 集合（如不存在）。
 * 单独成一个 Component，只依赖 qdrantClient Bean，自身不被其他 Bean 依赖，
 * 因此不会卷入任何循环引用。
 */
@Slf4j
@Component
public class QdrantCollectionInitializer {

    @Resource
    private QdrantClient qdrantClient;

    @Value("${qdrant.collection-name}")
    private String collectionName;

    @Value("${qdrant.vector-size}")
    private Integer vectorSize;

    @Value("${qdrant.distance-type}")
    private String distanceType;

    @PostConstruct
    public void initCollection() {
        try {
            boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();

            if (!exists) {
                log.info("创建 Qdrant 集合: {}", collectionName);

                Collections.Distance distance = parseDistance(distanceType);

                qdrantClient.createCollectionAsync(
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

        } catch (Exception e) {
            log.error("初始化 Qdrant 集合失败", e);
            throw new RuntimeException("初始化 Qdrant 集合失败", e);
        }
    }

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
