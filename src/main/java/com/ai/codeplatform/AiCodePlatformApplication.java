package com.ai.codeplatform;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.TimeZone;

@EnableCaching
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.ai.codeplatform.mapper")
@EnableAspectJAutoProxy(exposeProxy = true)
public class AiCodePlatformApplication {

    public static void main(String[] args) {
        // 统一时区为上海，避免 LocalDateTime.now() 及统计“今日/本周”边界按 UTC 计算导致偏差 8 小时
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(AiCodePlatformApplication.class, args);
    }

}
