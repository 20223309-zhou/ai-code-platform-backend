package com.ai.codeplatform;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableCaching
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.ai.codeplatform.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
public class AiCodePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodePlatformApplication.class, args);
    }

}
