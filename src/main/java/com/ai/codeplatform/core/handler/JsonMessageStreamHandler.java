package com.ai.codeplatform.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.ai.model.message.*;
import com.ai.codeplatform.ai.tools.BaseTool;
import com.ai.codeplatform.ai.tools.ToolManager;
import com.ai.codeplatform.model.entity.ChatHistoryOriginal;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.model.enums.ChatHistoryMessageTypeEnum;
import com.ai.codeplatform.service.ChatHistoryOriginalService;
import com.ai.codeplatform.service.ChatHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    @Resource
    private ToolManager toolManager;

    /**
     * 处理 TokenStream
     * 解析 JSON 消息并重组为完整的响应格式
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    public Flux<String> handle(Flux<String> originFlux,
                               ChatHistoryService chatHistoryService,
                               ChatHistoryOriginalService chatHistoryOriginalService,
                               long appId, User loginUser) {
        // 收集数据用于前端展示
        StringBuilder chatHistoryStringBuilder = new StringBuilder();
        // 收集用于恢复对话记忆的数据
        StringBuilder aiResponseStringBuilder = new StringBuilder();
        // 每个 Flux 流可能包含多条工具调用和 AI_RESPONSE 响应信息，统一收集之后批量入库
        List<ChatHistoryOriginal> originalChatHistoryList = new ArrayList<>();
        // 用于跟踪已经见过的工具名，同一工具多次调用只显示一次选择消息
        Set<String> seenToolNames = new HashSet<>();
        return originFlux
                .map(chunk -> {
                    // 解析每个 JSON 消息块
                    return handleJsonMessageChunk(chunk, chatHistoryStringBuilder, aiResponseStringBuilder, originalChatHistoryList, seenToolNames);
                })
                .filter(StrUtil::isNotEmpty) // 过滤空字串
                .doOnComplete(() -> {
                    // 工具调用信息入库
                    if (!originalChatHistoryList.isEmpty()) {
                        // 完善 ChatHistoryOriginal 信息
                        originalChatHistoryList.forEach(chatHistory -> {
                            chatHistory.setAppId(appId);
                            chatHistory.setUserId(loginUser.getId());
                        });
                        // 批量入库
                        chatHistoryOriginalService.addOriginalChatMessageBatch(originalChatHistoryList);
                    }
                    // Ai response 入库（只有当有内容时才保存）
                    String aiResponseStr = aiResponseStringBuilder.toString();
                    if (StrUtil.isNotBlank(aiResponseStr)) {
                        chatHistoryOriginalService.addOriginalChatMessage(appId, aiResponseStr, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }

                    // 流式响应完成后，添加 AI 消息到对话历史（只有当有内容时才保存）
                    String chatHistoryStr = chatHistoryStringBuilder.toString();
                    if (StrUtil.isNotBlank(chatHistoryStr)) {
                        chatHistoryService.addChatMessage(appId, chatHistoryStr, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    }
                })
                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    chatHistoryService.addChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                    chatHistoryOriginalService.addOriginalChatMessage(appId, errorMessage, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }

    /**
     * 解析并收集 TokenStream 数据
     */
    private String handleJsonMessageChunk(String chunk,
                                          StringBuilder chatHistoryStringBuilder,
                                          StringBuilder aiResponseStringBuilder,
                                          List<ChatHistoryOriginal> originalChatHistoryList,
                                          Set<String> seenToolNames) {
        // 解析 JSON
        StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
        StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
        switch (typeEnum) {
            case AI_RESPONSE -> {
                AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
                String data = aiMessage.getData();
                // 直接拼接响应
                chatHistoryStringBuilder.append(data);
                // 对于 AI 响应内容，与展示数据处理逻辑相同
                aiResponseStringBuilder.append(data);
                return data;
            }
            case TOOL_REQUEST -> {
                ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
                String toolName = toolRequestMessage.getName();
                // 同一工具多次调用只显示一次选择消息
                if (toolName != null && !seenToolNames.contains(toolName)) {
                    // 第一次遇到这个工具，记录并显示选择消息
                    seenToolNames.add(toolName);
                    // 获取工具实例
                    BaseTool tool = toolManager.getTool(toolName);
                    if (tool != null) {
                        return tool.generateToolRequestResponse();
                    }
                    else if("activate_skill".equals(toolName)){
                        return String.format("\n\n\uD83D\uDEE0\uFE0F[选择工具] %s\n\n", "激活Skill");
                    }else if("read_skill_resource".equals(toolName)){
                        return String.format("\n\n\uD83D\uDEE0\uFE0F[选择工具] %s\n\n", "使用Skill");
                    }else {
                        log.warn("未找到对应的工具: {}, 跳过处理", toolName);
                        return String.format("\n\n⚠️[未知工具] %s\n\n", toolName);
                    }
                } else {
                    // 不是第一次调用这个工具，直接返回空
                    return "";
                }
            }
            case TOOL_EXECUTED -> {
                // 处理工具调用信息
                processToolExecutionMessage(aiResponseStringBuilder, chunk, originalChatHistoryList);
                // 格式化处理
                ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
                String toolName = toolExecutedMessage.getName();
                JSONObject arguments = JSONUtil.parseObj(toolExecutedMessage.getArguments());
                // 根据工具名称获取工具实例并生成格式化输出信息
                BaseTool tool = toolManager.getTool(toolName);
                if (tool != null){
                    String result = tool.generateToolExecutedResult(arguments);
                    // 输出前端和要持久化的内容
                    String output = String.format("\n\n%s\n\n", result);
                    chatHistoryStringBuilder.append(output);
                    return output;
                }else if("activate_skill".equals(toolName)){
                    String output = String.format("\n\n\uD83D\uDCD6[工具调用] %s%s\n\n", "使用Skill：",arguments.get("skill_name"));
                    chatHistoryStringBuilder.append(output);
                    return output;
                }else if("read_skill_resource".equals(toolName)){
                    if (StrUtil.isNotBlank(arguments.get("relative_path").toString())){
                        String output = String.format("\n\n\uD83D\uDCBC[工具调用] %s%s/%s\n\n", "阅读Skill.md中：",arguments.get("skill_name"),arguments.get("relative_path"));
                        chatHistoryStringBuilder.append(output);
                        return output;
                    }
                    String output = String.format("\n\n\uD83D\uDCBC[工具调用] %s%s\n\n", "调用Skill：",arguments.get("skill_name"));
                    chatHistoryStringBuilder.append(output);
                    return output;
                }else {
                    log.warn("未找到对应的工具: {}, 使用默认格式化", toolName);
                    String output = String.format("\n\n⚠️[工具执行结果] %s\n参数: %s\n", toolName, arguments);
                    chatHistoryStringBuilder.append(output);
                    return output;
                }
            }
            case THINKING -> {
                // 推理过程：直接透传原始 JSON，前端自行解析渲染
                return chunk;
            }
            default -> {
                log.error("不支持的消息类型: {}", typeEnum);
                return "";
            }
        }
    }


    /**
     * 解析处理工具调用相关信息
     * @param aiResponseStringBuilder
     * @param chunk
     * @param originalChatHistoryList
     */
    private void processToolExecutionMessage(StringBuilder aiResponseStringBuilder, String chunk, List<ChatHistoryOriginal> originalChatHistoryList) {
        // 解析 chunk
        ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
        // 构造工具调用请求对象(工具调用结果的数据就是从调用请求里拿的，所以直接在这里处理调用请求信息)
        String aiResponseStr = aiResponseStringBuilder.toString();
        ToolRequestMessage toolRequestMessage = new ToolRequestMessage();
        toolRequestMessage.setId(toolExecutedMessage.getId());
        toolRequestMessage.setName(toolExecutedMessage.getName());
        toolRequestMessage.setArguments(toolExecutedMessage.getArguments());
        toolRequestMessage.setText(aiResponseStr);
        // 转换成 JSON
        String toolRequestJsonStr = JSONUtil.toJsonStr(toolRequestMessage);
        // 构造 ChatHistory 存入列表
        ChatHistoryOriginal toolRequestHistory = ChatHistoryOriginal.builder()
                .message(toolRequestJsonStr)
                .messageType(ChatHistoryMessageTypeEnum.TOOL_EXECUTION_REQUEST.getValue())
                .build();
        originalChatHistoryList.add(toolRequestHistory);
        ChatHistoryOriginal toolResultHistory = ChatHistoryOriginal.builder()
                .message(chunk)
                .messageType(ChatHistoryMessageTypeEnum.TOOL_EXECUTION_RESULT.getValue())
                .build();
        originalChatHistoryList.add(toolResultHistory);
        // AI 响应内容暂时结束，置空 aiResponseStringBuilder
        aiResponseStringBuilder.setLength(0);
    }
}

