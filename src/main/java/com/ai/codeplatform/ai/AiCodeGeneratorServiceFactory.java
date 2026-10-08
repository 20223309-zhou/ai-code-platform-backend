package com.ai.codeplatform.ai;

import com.ai.codeplatform.ai.guardrail.PromptSafetyInputGuardrail;
import com.ai.codeplatform.ai.tools.*;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.model.enums.CodeGenTypeEnum;
import com.ai.codeplatform.model.enums.ModelEnum;
import com.ai.codeplatform.service.ChatHistoryOriginalService;
import com.ai.codeplatform.utils.SpringContextUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.skills.Skills;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    /**
     * 对话记忆窗口上限：同时限制内存驻留与 Redis 中该 appId 的消息条数。
     * 原值 80000 过大——必然超出模型上下文（无意义），且有 OOM 风险。
     */
    private static final int MAX_MEMORY_MESSAGES = 200;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatHistoryOriginalService chatHistoryOriginalService;

    @Resource
    private ToolManager toolManager;

    @Resource
    private RetrievalAugmentor retrievalAugmentor;

    @Resource
    private Skills skills;

    @Resource
    private SearchImageTool searchImageTool;

    @Resource
    private GenerateLogoTool generateLogoTool;
    /**
     * AI 服务实例缓存（按 appId + 生成类型 + 模型 缓存）
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            //记录缓存淘汰日志
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除，缓存键: {}, 原因: {}", key, cause);
            })
            .build();

    /**
     * 对话记忆缓存：按 appId 共享（与生成类型、所选模型无关），保证切换模型时上下文连续。
     * <p>
     * 使用 Caffeine 有界缓存，避免此前 {@code ConcurrentHashMap} 永不清理导致的内存泄漏；
     * 记忆本体持久化在 Redis(chatMemoryStore)，实例被淘汰后可按需重新加载。
     */
    private final Cache<Long, MessageWindowChatMemory> memoryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .removalListener((key, value, cause) -> log.debug("对话记忆被移除，appId: {}, 原因: {}", key, cause))
            .build();

    /**
     * 根据 appId 获取服务（带缓存）这个方法是为了兼容历史逻辑
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML, ModelEnum.DEEP_SEEK.getModelName());
    }

    /**
     * 根据 appId、代码生成类型与模型获取服务（带缓存）
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType,String modelName) {
        String cacheKey = buildCacheKey(appId, codeGenType,modelName);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType,modelName));
    }

    /**
     * 在调用 AI 服务前，把用户消息中的图片注入到该 appId 的记忆中。
     * <p>
     * 由于 langchain4j 的 {@code @UserMessage UserMessage} 参数会被序列化为对象的 toString()，
     * 导致多模态内容（图片）丢失，因此图片改为通过记忆（ChatMemory）传入，
     * 文本仍通过 {@code @UserMessage String} 参数传入。
     *
     * @param appId       应用 ID
     * @param userMessage 包含图片的用户消息
     */
    public void addUserImagesToMemory(Long appId, UserMessage userMessage) {
        if (appId == null) {
            return;
        }
        MessageWindowChatMemory chatMemory = memoryCache.getIfPresent(appId);
        if (chatMemory == null) {
            return;
        }
        List<Content> images = userMessage.contents().stream()
                .filter(content -> content instanceof ImageContent)
                .collect(Collectors.toList());
        if (!images.isEmpty()) {
            chatMemory.add(UserMessage.from(images));
        }
    }

    /**
     * 创建新的 AI 服务实例
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType,String modelName) {
        // 对话记忆按 appId 共享（与类型/模型无关），保证切换模型后上下文不丢
        MessageWindowChatMemory chatMemory = getOrCreateChatMemory(appId);
        // 按所选模型解析流式 / 非流式模型（未知模型回退 DeepSeek）
        StreamingChatModel streamingChatModel = resolveStreamingChatModel(modelName);
        ChatModel chatModel = resolveChatModel(modelName);
        // 根据代码生成类型选择不同的工具与 RAG 配置
        return switch (codeGenType) {
            case VUE_PROJECT ->
                // 使用多例模式(prototype)的模型实例解决并发问题
                AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .streamingChatModel(streamingChatModel)
                        .retrievalAugmentor(retrievalAugmentor)
                        .toolProvider(skills.toolProvider())
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools(toolManager.getAllTools())
                        // 添加输入护轨
                        .inputGuardrails(new PromptSafetyInputGuardrail())
                        .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                                toolExecutionRequest, "Error: there is no tool called " + toolExecutionRequest.name()
                        ))
                        .build();
            case HTML ->
                AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .streamingChatModel(streamingChatModel)
                        .toolProvider(skills.toolProvider())
                        .retrievalAugmentor(retrievalAugmentor)
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools(new WebFetchTool(), new FileReadTool(),
                                new FileModifyTool(), searchImageTool, generateLogoTool)
                        // 添加输入护轨
                        .inputGuardrails(new PromptSafetyInputGuardrail())
                        .build();
            case MULTI_FILE ->
                AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .streamingChatModel(streamingChatModel)
                        .toolProvider(skills.toolProvider())
                        .retrievalAugmentor(retrievalAugmentor)
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools(new WebFetchTool(), new FileReadTool(),
                                new FileModifyTool(), new FileWriteTool(),
                                searchImageTool, generateLogoTool)
                        // 添加输入护轨
                        .inputGuardrails(new PromptSafetyInputGuardrail())
                        .build();
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue());
        };
    }

    /**
     * 获取（或创建）appId 对应的对话记忆。
     * <p>
     * 使用 Caffeine 的 {@code get(key, mappingFunction)}，保证并发下同一 appId 只加载一次历史，
     * 避免"切换模型/类型时重复加载历史导致消息重复"。
     */
    private MessageWindowChatMemory getOrCreateChatMemory(long appId) {
        return memoryCache.get(appId, id -> {
            MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                    .builder()
                    .id(appId)
                    .chatMemoryStore(redisChatMemoryStore)
                    .maxMessages(MAX_MEMORY_MESSAGES)
                    .build();
            // 从数据库加载历史对话到记忆中（按 token 预算回放，保证语义主线不丢）
            chatHistoryOriginalService.loadOriginalChatHistoryToMemory(appId, chatMemory);
            return chatMemory;
        });
    }

    /**
     * 按模型名解析流式模型（多例 prototype，解决并发问题）；未知模型回退 DeepSeek。
     */
    private StreamingChatModel resolveStreamingChatModel(String modelName) {
        return switch (resolveModelEnum(modelName)) {
            case DEEP_SEEK ->
                    SpringContextUtil.getBean("deepseekReasoningStreamingChatModelPrototype", StreamingChatModel.class);
            case GROK ->
                    SpringContextUtil.getBean("grokReasoningStreamingChatModelPrototype", StreamingChatModel.class);
            case CHAT_GPT ->
                    SpringContextUtil.getBean("chatgptReasoningStreamingChatModelPrototype", StreamingChatModel.class);
        };
    }

    /**
     * 按模型名解析非流式模型；未知模型回退 DeepSeek。
     */
    private ChatModel resolveChatModel(String modelName) {
        return switch (resolveModelEnum(modelName)) {
            case DEEP_SEEK -> SpringContextUtil.getBean("deepseekChatModelPrototype", ChatModel.class);
            case GROK -> SpringContextUtil.getBean("grokChatModelPrototype", ChatModel.class);
            case CHAT_GPT -> SpringContextUtil.getBean("chatgptChatModelPrototype", ChatModel.class);
        };
    }

    /**
     * 模型名 -> 枚举；为空或未知时回退默认模型 DeepSeek。
     */
    private ModelEnum resolveModelEnum(String modelName) {
        ModelEnum modelEnum = ModelEnum.getEnumByModelName(modelName);
        return modelEnum != null ? modelEnum : ModelEnum.DEEP_SEEK;
    }


    /**
     * 默认提供一个 Bean
     */
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return getAiCodeGeneratorService(0L);
    }

    /**
     * 构建缓存键
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType,String modelName) {
        return appId + "_" + codeGenType.getValue() + "_" + modelName;
    }

}
