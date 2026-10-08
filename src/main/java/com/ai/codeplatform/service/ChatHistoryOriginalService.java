package com.ai.codeplatform.service;

import com.mybatisflex.core.service.IService;
import com.ai.codeplatform.model.entity.ChatHistoryOriginal;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.List;

/**
 * 原始对话历史 服务层。
 * 为 vue 工程模式恢复对话记忆(包含工具调用信息)
 *
 * @author agx
 */
public interface ChatHistoryOriginalService extends IService<ChatHistoryOriginal> {


    /**
     * 添加对话历史
     *
     * @param appId
     * @param message
     * @param messageType
     * @param userId
     * @return
     */
    boolean addOriginalChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 批量添加对话历史
     *
     * @param chatHistoryOriginalList
     * @return
     */
    boolean addOriginalChatMessageBatch(List<ChatHistoryOriginal> chatHistoryOriginalList);

    /**
     * 根据 appId 关联删除对话历史记录
     *
     * @param appId
     * @return
     */
    boolean deleteByAppId(Long appId);

    /**
     * 将 APP 的对话历史加载到缓存中（推荐）。
     * 走 {@link com.ai.codeplatform.config.ChatHistoryReplayProperties} 的 token 预算策略，
     * 保证回放窗口里一定有 user / ai 的语义主线。
     *
     * @param appId
     * @param chatMemory
     * @return 实际加载的记录数
     */
    int loadOriginalChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory);

    /**
     * 将 APP 的对话历史加载到缓存中。
     *
     * @param appId
     * @param chatMemory
     * @param maxCount 扫描窗口上限（记录条数），会与配置的 scanLimit 取较小值
     * @return 实际加载的记录数
     */
    int loadOriginalChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount);
}


