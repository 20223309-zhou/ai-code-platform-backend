package com.ai.codeplatform.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * 方案预选模型配置。
 * 为什么要单独一套配置，而不是复用 {@code routing-chat-model}：
 * <ul>
 *   <li>路由模型只输出一个枚举值，{@code max-tokens: 100} 足够；</li>
 *   <li>方案预选要输出 1~3 个方案的完整 JSON（标题/风格/布局/描述/标签），
 *       100 token 会被直接截断，必须单独放大到 2000；</li>
 *   <li>两者语义也不同：路由是分类任务，方案是生成任务。</li>
 * </ul>
 * <p>
 * 为什么不再用推理模型：{@code AppPlanService} 是一次纯文本 + 结构化输出的轻量调用，
 * 不挂工具、不挂记忆、不挂 RAG。之前注入 {@code deepseekChatModelPrototype}，
 * 导致方案生成依赖 DeepSeek 的 key —— 一旦这个 key 失效，所有应用（与生成类型无关）
 * 的方案预选都会失败并静默降级。改用轻量模型后，方案与代码生成的主模型解耦。
 */
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.plan-chat-model")
@Data
public class PlanAiModelConfig {

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer maxTokens;

    private Double temperature;

    private Boolean logRequests = false;

    private Boolean logResponses = false;

    /**
     * 创建用于方案预选的 ChatModel。
     * prototype 语义：每次注入时新建一个实例，避免并发共享同一个模型对象
     * （ChatModel 内部持有连接池，共享在并发下会互相影响）。
     */
    @Bean
    @Scope("prototype")
    public ChatModel planChatModelPrototype() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
