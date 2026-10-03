package com.ai.codeplatform.rag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享的 Qdrant gRPC 客户端。独立成一个配置类，既能供集合初始化使用，
 * 也能供文档遍历（scroll）复用，且本身不依赖任何业务 Bean，避免循环引用。
 */
@Slf4j
@Configuration
public class QdrantClientConfig {

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.port}")
    private Integer port;

    @Bean
    public QdrantClient qdrantClient() {
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, port, false)
                .build();
        return new QdrantClient(grpcClient);
    }
}
