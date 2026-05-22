package com.ai.codeplatform.rag.config;

import dev.langchain4j.model.embedding.onnx.bgesmallzhv15.BgeSmallZhV15EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingModelConfig {

    @Bean
    public BgeSmallZhV15EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15EmbeddingModel();
    }

}
