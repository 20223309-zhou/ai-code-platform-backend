package com.ai.codeplatform.config;

import com.ai.codeplatform.ai.memory.CompressingChatMemoryStore;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话记忆存储配置。
 * <p>
 * 真实存储在 Redis，外面包一层 {@link CompressingChatMemoryStore}：
 * <ul>
 *   <li><b>压缩</b>：历史里 {@code readFile} / {@code readDir} / {@code fetchWebPage} 的结果
 *       动辄上万字符，而每次 {@code add()} 都要把它们连同整个列表重新 SETEX 一遍。
 *       压缩后单次写回的 payload 从几百 KB 降到几十 KB，同时降低每轮模型请求的上下文成本。</li>
 *   <li><b>容错</b>：写失败只记 WARN，不再让"内容已流式输出完成"的请求以 500 收尾。</li>
 * </ul>
 * 开关见 {@code app.memory.*}，出问题可立即把 {@code compress-read-results} 置 false 回退。
 */
@Configuration
@ConfigurationProperties(prefix = "spring.data.redis")
@Data
public class RedisChatMemoryStoreConfig {

    private String host;

    private int port;

    private String password;

    private long ttl;

    private String userName;

    /**
     * 是否压缩历史中的读类工具结果。默认开启。
     */
    @Value("${app.memory.compress-read-results:true}")
    private boolean compressReadResults;

    /**
     * 最近多少条读类工具结果保留全文。默认 6：
     * 正在改的文件需要模型直接可见，压掉会引发反复 readFile。
     */
    @Value("${app.memory.keep-full-results:6}")
    private int keepFullResults;

    // 会话记忆存储到 redis 中，外层包一层压缩 + 容错
    @Bean
    public ChatMemoryStore chatMemoryStore() {
        RedisChatMemoryStore redisStore = RedisChatMemoryStore.builder()
                .user(userName)
                .host(host)
                .port(port)
                .password(password)
                .ttl(ttl)
                .build();
        return new CompressingChatMemoryStore(redisStore, compressReadResults, keepFullResults);
    }
}
