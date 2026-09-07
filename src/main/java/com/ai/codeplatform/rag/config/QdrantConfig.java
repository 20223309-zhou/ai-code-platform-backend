package com.ai.codeplatform.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant 向量数据库配置（仅提供 EmbeddingStore Bean，避免与 qdrantClient / 集合初始化互相依赖形成循环引用）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "qdrant")
@Slf4j
public class QdrantConfig {

    private String host;
    private Integer port;
    private String collectionName;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host(host)
                .port(port)
                .collectionName(collectionName)
                .build();
    }
}
