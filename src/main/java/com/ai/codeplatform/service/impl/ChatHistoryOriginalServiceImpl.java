package com.ai.codeplatform.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.ai.model.message.ToolExecutedMessage;
import com.ai.codeplatform.ai.model.message.ToolRequestMessage;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.model.enums.ChatHistoryMessageTypeEnum;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ai.codeplatform.model.entity.ChatHistoryOriginal;
import com.ai.codeplatform.mapper.ChatHistoryOriginalMapper;
import com.ai.codeplatform.service.ChatHistoryOriginalService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 原始对话历史 服务层实现。
 * 为 vue 工程模式恢复对话记忆(包含工具调用信息)
 *
 */
@Service
@Slf4j
public class ChatHistoryOriginalServiceImpl extends ServiceImpl<ChatHistoryOriginalMapper, ChatHistoryOriginal> implements ChatHistoryOriginalService {

    /**
     * 添加AI对话消息
     * @param appId       应用 ID
     * @param message     消息内容
     * @param messageType 消息类型
     * @param userId      用户 ID
     * @return 是否添加成功
     */
    @Override
    public boolean addOriginalChatMessage(Long appId, String message, String messageType, Long userId) {
        // 参数校验
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID不能为空");
        }
        if (StrUtil.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        }
        if (StrUtil.isBlank(messageType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息类型不能为空");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 ID不能为空");
        }
        // 验证消息类型是否有效
        ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
        if (messageTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的消息类型: " + messageType);
        }
        // 对话消息入库
        ChatHistoryOriginal chatHistoryOriginal = ChatHistoryOriginal.builder()
                .appId(appId)
                .message(message)
                .messageType(messageType)
                .userId(userId)
                .build();
        return this.save(chatHistoryOriginal);
    }

    /**
     * 批量添加AI对话消息
     * @param chatHistoryOriginalList 批量对话消息
     * @return 是否添加成功
     */
    @Override
    public boolean addOriginalChatMessageBatch(List<ChatHistoryOriginal> chatHistoryOriginalList) {
        // 参数校验
        if (chatHistoryOriginalList == null || chatHistoryOriginalList.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息列表不能为空");
        }

        // 验证消息类型是否有效，无效类型的对话记录不进行入库
        List<ChatHistoryOriginal> validMessages = chatHistoryOriginalList.stream()
                .filter(chatHistory -> {
                    ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(chatHistory.getMessageType());
                    if (messageTypeEnum == null) {
                        log.error("不支持的消息类型: {}", chatHistory.getMessageType());
                        return false; // 过滤掉无效消息
                    }
                    return true; // 保留有效消息
                })
                .collect(Collectors.toList());

        // 如果没有有效消息，直接返回
        if (validMessages.isEmpty()) {
            return false;
        }

        // 批量入库
        return this.saveBatch(validMessages);
    }


    @Override
    public boolean deleteByAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID不能为空");
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("appId", appId);
        return this.remove(queryWrapper);
    }

    /**
     * 加载原始对话历史到内存
     * @param appId       应用 ID
     * @param chatMemory  对话记忆
     * @param maxCount    最大加载数量
     * @return 加载成功的数量
     */
    @Override
    public int loadOriginalChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount) {
        try {
            // 1. 查询历史记录，考虑边缘记录类型
            List<ChatHistoryOriginal> originalHistoryList = queryHistoryWithEdgeCheck(appId, maxCount);
            if (CollUtil.isEmpty(originalHistoryList)) {
                return 0;
            }

            // 2. 反转列表，确保时间正序(老的在前，新的在后)
            originalHistoryList = originalHistoryList.reversed();

            // 3. 先清理当前 app 的历史缓存，防止重复加载
            chatMemory.clear();

            // 4. 遍历原始历史记录，根据类型将消息添加到记忆中
            int loadedCount = loadMessagesToMemory(originalHistoryList, chatMemory);

            log.info("成功为 appId: {} 加载 {} 条历史对话", appId, loadedCount);
            return loadedCount;
        } catch (Exception e) {
            log.error("加载历史对话失败，appId: {}，error: {}", appId, e.getMessage(), e);
            // 加载失败不影响系统运行，只是没有历史上下文
            return 0;
        }
    }

    /**
     * 查询历史记录，考虑边缘记录类型
     * 工具调用信息必须是成对并且有序的: tool_request -> tool_result，否则就会报错！
     * 错误信息：dev.langchain4j.exception.HttpException: {"error":{"message":"Messages with role 'tool' must be a response to a preceding message with 'tool_calls'","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}
     * 1. 边缘检查的意义在于当查询到的第 maxCount + 1 那条数据是 tool_result 时就丢失了一条 tool_request，导致报错
     * 2. 这里改为了按 id 倒序查询，时间戳排序可能因为相近值而不稳定，当 tool_request 和 tool_result 的顺序加载错了会导致报错（MyBatis-flex的雪花算法生成的ID是严格递增的）
     *
     * @param appId    应用ID
     * @param maxCount 最大记录数
     * @return 历史记录列表
     */
    private List<ChatHistoryOriginal> queryHistoryWithEdgeCheck(Long appId, int maxCount) {
        // 1. 首先检查总记录数
        QueryWrapper countQueryWrapper = QueryWrapper.create()
                .eq(ChatHistoryOriginal::getAppId, appId);
        long totalCount = this.count(countQueryWrapper);

        // 2. 如果总记录数小于等于1，直接返回空列表（因为我们要跳过第1条记录）
        if (totalCount <= 1) {
            log.debug("总记录数 ({}) 小于等于1，没有足够的历史记录可加载", totalCount);
            return Collections.emptyList();
        }

        // 3. 计算实际可查询的最大记录数（减去要跳过的第1条记录）
        long availableCount = totalCount - 1;

        // 4. 如果总记录数小于等于 maxCount+1，则不需要检查边缘记录
        if (totalCount <= maxCount + 1) {
            log.debug("总记录数 ({}) 小于等于 maxCount+1 ({}), 不需要检查边缘记录", totalCount, maxCount + 1);

            // 直接查询所有可用记录（跳过最新的用户消息）
            QueryWrapper queryWrapper = QueryWrapper.create()
                    .eq(ChatHistoryOriginal::getAppId, appId)
                    .orderBy(ChatHistoryOriginal::getId, false) // 使用ID倒序，确保顺序性
                    .limit(1, availableCount);  // 查询从第2条开始的所有可用记录

            return this.list(queryWrapper);
        }

        // 5. 如果总记录数大于 maxCount+1，则需要检查边缘记录
        // 查询第 maxCount+1 条记录（边缘记录）
        QueryWrapper edgeQueryWrapper = QueryWrapper.create()
                .eq(ChatHistoryOriginal::getAppId, appId)
                .orderBy(ChatHistoryOriginal::getId, false)
                .limit(maxCount, 1);  // 查询第 maxCount+1 条记录

        ChatHistoryOriginal edgeRecord = this.getOne(edgeQueryWrapper);

        // 6. 如果边缘记录是 TOOL_EXECUTION_RESULT 类型，则需要额外查询其前一条 TOOL_EXECUTION_REQUEST 记录
        boolean needExtraRequest = false;
        if (edgeRecord != null) {
            String edgeMessageType = edgeRecord.getMessageType();
            ChatHistoryMessageTypeEnum edgeMessageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(edgeMessageType);
            needExtraRequest = (edgeMessageTypeEnum == ChatHistoryMessageTypeEnum.TOOL_EXECUTION_RESULT);
        }

        // 7. 计算实际需要查询的记录数
        long actualLimit = Math.min(needExtraRequest ? maxCount + 1 : maxCount, availableCount);

        // 8. 查询历史记录
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(ChatHistoryOriginal::getAppId, appId)
                .orderBy(ChatHistoryOriginal::getId, false)
                .limit(1, actualLimit);  // 查询从第2条开始的 actualLimit 条记录

        List<ChatHistoryOriginal> originalHistoryList = this.list(queryWrapper);
        if (CollUtil.isEmpty(originalHistoryList)) {
            return Collections.emptyList();
        }

        // 9. 检查是否需要调整 maxCount
        if (needExtraRequest && originalHistoryList.size() <= maxCount) {
            // 如果需要额外的 TOOL_EXECUTION_REQUEST 但没有获取到足够的记录
            log.warn("边缘记录是 TOOL_EXECUTION_RESULT 类型，但未获取到足够的记录包含 TOOL_EXECUTION_REQUEST，将 maxCount 减 1");
            maxCount = Math.max(0, maxCount - 1);  // 确保 maxCount 不小于 0

            // 如果 maxCount 变为 0，则直接返回空列表
            if (maxCount == 0) {
                log.info("调整后 maxCount 为 0，不加载任何历史记录");
                return Collections.emptyList();
            }

            // 重新查询，使用调整后的 maxCount
            actualLimit = Math.min(maxCount, availableCount);
            queryWrapper = QueryWrapper.create()
                    .eq(ChatHistoryOriginal::getAppId, appId)
                    .orderBy(ChatHistoryOriginal::getId, false)
                    .limit(1, actualLimit);  // 查询从第2条开始的 actualLimit 条记录

            originalHistoryList = this.list(queryWrapper);
            if (CollUtil.isEmpty(originalHistoryList)) {
                return Collections.emptyList();
            }
        }

        return originalHistoryList;
    }

    /**
     * 将历史记录加载到内存中
     *
     * @param originalHistoryList 历史记录列表
     * @param chatMemory          聊天记忆
     * @return 加载的记录数
     */
    private int loadMessagesToMemory(List<ChatHistoryOriginal> originalHistoryList, MessageWindowChatMemory chatMemory) {
        int loadedCount = 0;
        // 遍历原始历史记录，根据类型将消息添加到记忆中
        for (ChatHistoryOriginal history : originalHistoryList) {
            // 这里需要根据消息类型进行转换，支持 AI, user, toolExecutionRequest, toolExecutionResult 4种类型
            String messageType = history.getMessageType();
            ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
            switch (messageTypeEnum) {
                case USER -> {
                    chatMemory.add(UserMessage.from(history.getMessage()));
                    loadedCount++;
                }
                case AI -> {
                    chatMemory.add(AiMessage.from(history.getMessage()));
                    loadedCount++;
                }
                case TOOL_EXECUTION_REQUEST -> {
                    ToolRequestMessage toolRequestMessage = JSONUtil.toBean(history.getMessage(), ToolRequestMessage.class);
                    ToolExecutionRequest toolExecutionRequest = ToolExecutionRequest.builder()
                            .id(toolRequestMessage.getId())
                            .name(toolRequestMessage.getName())
                            .arguments(toolRequestMessage.getArguments())
                            .build();
                    // 有些工具调用请求带有文本，有些没有
                    if (toolRequestMessage.getText().isEmpty()) {
                        chatMemory.add(AiMessage.from(List.of(toolExecutionRequest)));
                    } else {
                        chatMemory.add(AiMessage.from(toolRequestMessage.getText(), List.of(toolExecutionRequest)));
                    }
                    loadedCount++;
                }
                case TOOL_EXECUTION_RESULT -> {
                    ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(history.getMessage(), ToolExecutedMessage.class);
                    String id = toolExecutedMessage.getId();
                    String toolName = toolExecutedMessage.getName();
                    String toolExecutionResult = toolExecutedMessage.getResult();
                    chatMemory.add(ToolExecutionResultMessage.from(id, toolName, toolExecutionResult));
                    loadedCount++;
                }
                case null -> log.error("未知消息类型: {}", messageType);
            }
        }
        return loadedCount;
    }

}

