package com.ai.codeplatform.ai;

import com.ai.codeplatform.ai.guardrail.PromptSafetyInputGuardrail;
import com.ai.codeplatform.ai.tools.*;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.model.enums.CodeGenTypeEnum;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


@Slf4j
@Configuration
public class AiCodeGeneratorServiceFactory {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;

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
     * AI 服务实例缓存
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
     * 按 (appId_type) 缓存对话记忆实例，便于在调用 AI 服务前把多模态消息（图片）注入记忆，
     * 避免 langchain4j 对 {@code @UserMessage UserMessage} 参数做 toString 序列化导致图片丢失
     */
    private final Map<String, MessageWindowChatMemory> memoryCache = new ConcurrentHashMap<>();

    /**
     * 根据 appId 获取服务（带缓存）这个方法是为了兼容历史逻辑
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
        return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
    }

    /**
     * 根据 appId 和代码生成类型获取服务（带缓存）
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        String cacheKey = buildCacheKey(appId, codeGenType);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
    }

    /**
     * 在调用 AI 服务前，把用户消息中的图片注入到对应记忆中。
     * <p>
     * 由于 langchain4j 的 {@code @UserMessage UserMessage} 参数会被序列化为对象的 toString()，
     * 导致多模态内容（图片）丢失，因此图片改为通过记忆（ChatMemory）传入，
     * 文本仍通过 {@code @UserMessage String} 参数传入。
     *
     * @param appId      应用 ID
     * @param codeGenType 生成类型（与记忆缓存键一致）
     * @param userMessage 包含图片的用户消息
     */
    public void addUserImagesToMemory(Long appId, CodeGenTypeEnum codeGenType, UserMessage userMessage) {
        MessageWindowChatMemory chatMemory = memoryCache.get(buildCacheKey(appId, codeGenType));
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
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        // 根据 appId 构建独立的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(appId)
                .chatMemoryStore(redisChatMemoryStore)
                // 对话记忆最大条数
                .maxMessages(80000)
                .build();
        // 从数据库加载历史对话到记忆中
        chatHistoryOriginalService.loadOriginalChatHistoryToMemory(appId, chatMemory, 20);
        // 缓存记忆实例，供调用前注入多模态图片
        memoryCache.put(buildCacheKey(appId, codeGenType), chatMemory);
        // 根据代码生成类型选择不同的模型配置
        return switch (codeGenType) {
            case VUE_PROJECT -> {
                // 使用多例模式的 StreamingChatModel 解决并发问题
                StreamingChatModel reasoningStreamingChatModel = SpringContextUtil.getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class);
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .streamingChatModel(reasoningStreamingChatModel)
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
            }
            case HTML -> {
                // 使用多例模式的 StreamingChatModel 解决并发问题
                StreamingChatModel openAiStreamingChatModel = SpringContextUtil.getBean("streamingChatModelPrototype", StreamingChatModel.class);
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .toolProvider(skills.toolProvider())
                        .streamingChatModel(openAiStreamingChatModel)
                        .retrievalAugmentor(retrievalAugmentor)
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools(new WebFetchTool(), new FileReadTool(),
                                new FileModifyTool(), searchImageTool, generateLogoTool)
                        // 添加输入护轨
                        .inputGuardrails(new PromptSafetyInputGuardrail())
                        .build();
            }
            case MULTI_FILE -> {
                // 使用多例模式的 StreamingChatModel 解决并发问题
                StreamingChatModel multiStreamingChatModel = SpringContextUtil.getBean("streamingChatModelPrototype", StreamingChatModel.class);
                yield AiServices.builder(AiCodeGeneratorService.class)
                        .chatModel(chatModel)
                        .toolProvider(skills.toolProvider())
                        .streamingChatModel(multiStreamingChatModel)
                        .retrievalAugmentor(retrievalAugmentor)
                        .chatMemoryProvider(memoryId -> chatMemory)
                        .tools(new WebFetchTool(), new FileReadTool(),
                                new FileModifyTool(), new FileWriteTool(),
                                searchImageTool, generateLogoTool)
                        // 添加输入护轨
                        .inputGuardrails(new PromptSafetyInputGuardrail())
                        .build();
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue());
        };
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
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
        return appId + "_" + codeGenType.getValue();
    }

}
