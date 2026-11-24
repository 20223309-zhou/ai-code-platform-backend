package com.ai.codeplatform.core.handler;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.ai.AiCodeGeneratorServiceFactory;
import com.ai.codeplatform.ai.model.message.*;
import com.ai.codeplatform.ai.tools.BaseTool;
import com.ai.codeplatform.ai.tools.ToolManager;
import com.ai.codeplatform.model.entity.ChatHistory;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON 消息流处理器
 * 处理 VUE_PROJECT 类型的复杂流式响应，包含工具调用信息
 */
@Slf4j
@Component
public class JsonMessageStreamHandler {

    /**
     * 中断标记：写进历史与记忆，让下一轮模型知道自己上一次没写完。
     */
    /**
     * 生成过程中的落库间隔（毫秒）。
     */
    private static final long CHECKPOINT_INTERVAL_MS = 1500;

    /**
     * 检查点内容的临时尾巴：让人一眼看出这条回复还没写完。
     * 正常结束时会被最终内容整行覆盖，中断时会被中断标记替换。
     */
    private static final String STREAMING_SUFFIX = "\n\n> ⏳ 生成中…";

    private static final String INTERRUPT_MARKER = "\n\n> ⏹ 本次生成已中断。";

    @Resource
    private ToolManager toolManager;

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

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
        // 服务端主动取消时 Facade 会先发一个中断哨兵，这里置位后按「中断」落库
        AtomicBoolean interrupted = new AtomicBoolean(false);
        // 检查点写入的那一行 chat_history 的 id：有它之后所有写入都走 update，保证一轮生成只有一条 AI 消息
        AtomicReference<Long> displayRowId = new AtomicReference<>();
        AtomicLong lastCheckpointAt = new AtomicLong(0);
        return originFlux
                .map(chunk -> {
                    // 解析每个 JSON 消息块
                    return handleJsonMessageChunk(chunk, chatHistoryStringBuilder, aiResponseStringBuilder,
                            originalChatHistoryList, seenToolNames, interrupted);
                })
                .filter(StrUtil::isNotEmpty) // 过滤空字串
                // 生成中按间隔把已生成文本落库，中断/刷新后能立刻看到断点内容
                .doOnNext(chunk -> checkpointIfDue(chatHistoryService, appId, loginUser,
                        chatHistoryStringBuilder, displayRowId, lastCheckpointAt))
                .doOnComplete(() -> {
                    // 正常写完是 false；服务端主动取消（收到过哨兵）走这里时是 true
                    boolean wasInterrupted = interrupted.get();
                    String partial = persistCollected(chatHistoryService, chatHistoryOriginalService,
                            appId, loginUser, originalChatHistoryList,
                            chatHistoryStringBuilder, aiResponseStringBuilder, wasInterrupted, displayRowId);
                    if (wasInterrupted) {
                        aiCodeGeneratorServiceFactory.appendAssistantTextToMemory(appId, partial);
                    }
                })
                .doOnCancel(() -> {
                    // 用户点「停止生成」时前端先断开 SSE，订阅收到的是 cancel 而不是 complete。
                    // 不在这里落库，已经生成的内容会随请求一起丢掉：重新进入会话只剩一条用户消息。
                    String partial = persistCollected(chatHistoryService, chatHistoryOriginalService,
                            appId, loginUser, originalChatHistoryList,
                            chatHistoryStringBuilder, aiResponseStringBuilder, true, displayRowId);
                    // langchain4j 只在响应完整结束时才把 AiMessage 写进 ChatMemory，
                    // 取消时补一条，否则同一会话里继续对话模型会完全不知道自己写到哪
                    aiCodeGeneratorServiceFactory.appendAssistantTextToMemory(appId, partial);
                })
                .doOnError(error -> {
                    // 如果AI回复失败，也要记录错误消息
                    String errorMessage = "AI回复失败: " + error.getMessage();
                    try {
                        // 已经写过检查点就更新那一行，避免同一轮生成留下两条 AI 消息
                        String partial = chatHistoryStringBuilder.toString();
                        if (displayRowId.get() != null) {
                            String content = StrUtil.isBlank(partial) ? errorMessage : partial + "\n\n> " + errorMessage;
                            saveOrUpdateDisplay(chatHistoryService, appId, loginUser, content, displayRowId);
                        } else {
                            chatHistoryService.addChatMessage(appId, errorMessage,
                                    ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                        }
                    } catch (Exception e) {
                        log.error("记录失败信息时出错，appId: {}", appId, e);
                    }
                    chatHistoryOriginalService.addOriginalChatMessage(appId, errorMessage,
                            ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                });
    }

    /**
     * 按固定间隔把"已生成的展示文本"写进 chat_history。
     * 只在内容发生变化且有内容时才写；写失败只记日志，绝不影响生成。
     */
    private void checkpointIfDue(ChatHistoryService chatHistoryService, long appId, User loginUser,
                                 StringBuilder displayText, AtomicReference<Long> displayRowId,
                                 AtomicLong lastCheckpointAt) {
        long now = System.currentTimeMillis();
        if (now - lastCheckpointAt.get() < CHECKPOINT_INTERVAL_MS) {
            return;
        }
        String content = displayText.toString();
        if (StrUtil.isBlank(content)) {
            return;
        }
        lastCheckpointAt.set(now);
        try {
            saveOrUpdateDisplay(chatHistoryService, appId, loginUser, content + STREAMING_SUFFIX, displayRowId);
        } catch (Exception e) {
            log.warn("生成中段落库失败（不影响生成），appId: {}, 原因: {}", appId, e.toString());
        }
    }

    /**
     * 写入展示用 AI 消息：没有检查点就插入并把 id 记下来，之后一律更新同一行。
     */
    private void saveOrUpdateDisplay(ChatHistoryService chatHistoryService, long appId, User loginUser,
                                     String content, AtomicReference<Long> displayRowId) {
        Long rowId = displayRowId.get();
        if (rowId == null) {
            ChatHistory row = ChatHistory.builder()
                    .appId(appId)
                    .userId(loginUser.getId())
                    .message(content)
                    .messageType(ChatHistoryMessageTypeEnum.AI.getValue())
                    .build();
            if (chatHistoryService.save(row)) {
                displayRowId.set(row.getId());
            }
        } else {
            // 只带 id 和 message：updateById 默认忽略 null 字段，不会动 createTime 等
            chatHistoryService.updateById(ChatHistory.builder().id(rowId).message(content).build());
        }
    }

    /**
     * 把流过程中收集到的内容落库。正常结束与中断共用同一套逻辑，只是中断时多一个标记。
     *
     * @param chatHistoryService         面向用户的会话历史
     * @param chatHistoryOriginalService 用于回放记忆的原始历史
     * @param appId        应用 ID
     * @param loginUser    登录用户
     * @param toolRecords  已发生的工具调用记录
     * @param displayText  给前端展示的完整文本
     * @param aiText       用于回放记忆的 AI 正文
     * @param interrupted  是否为中断场景
     * @return 实际写入的 AI 文本（中断时含标记）；没有任何内容时返回 null
     */
    private String persistCollected(ChatHistoryService chatHistoryService,
                                    ChatHistoryOriginalService chatHistoryOriginalService,
                                    long appId, User loginUser,
                                    List<ChatHistoryOriginal> toolRecords,
                                    StringBuilder displayText, StringBuilder aiText,
                                    boolean interrupted, AtomicReference<Long> displayRowId) {
        try {
            String aiContent = aiText.toString();
            String displayContent = displayText.toString();
            if (interrupted) {
                // 只在各自确实有内容时加标记：模型还在调工具、一个字都没产出时，
                // 不该凭空造出一条只含标记的空回复
                if (StrUtil.isNotBlank(aiContent)) {
                    aiContent = aiContent + INTERRUPT_MARKER;
                }
                if (StrUtil.isNotBlank(displayContent)) {
                    displayContent = displayContent + INTERRUPT_MARKER;
                }
            }
            // 工具调用信息入库
            if (!toolRecords.isEmpty()) {
                toolRecords.forEach(chatHistory -> {
                    chatHistory.setAppId(appId);
                    chatHistory.setUserId(loginUser.getId());
                });
                chatHistoryOriginalService.addOriginalChatMessageBatch(toolRecords);
            }
            // Ai response 入库（只有当有内容时才保存）
            if (StrUtil.isNotBlank(aiContent)) {
                chatHistoryOriginalService.addOriginalChatMessage(appId, aiContent,
                        ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
            }
            // 展示用消息：如果生成过程中已经写过检查点，就更新同一行，避免重复
            if (StrUtil.isNotBlank(displayContent)) {
                saveOrUpdateDisplay(chatHistoryService, appId, loginUser, displayContent, displayRowId);
            }
            if (interrupted) {
                log.info("生成被中断，已落库已生成内容，appId: {}, 长度: {}", appId, displayContent.length());
            }
            return StrUtil.isBlank(aiContent) ? null : aiContent;
        } catch (Exception e) {
            // 落库失败不能影响取消流程本身（此时连接已经断了，抛出去也没人接）
            log.error("保存对话历史失败，appId: {}, 中断: {}, 原因: {}", appId, interrupted, e.toString());
            return null;
        }
    }

    /**
     * 解析并收集 TokenStream 数据
     */
    private String handleJsonMessageChunk(String chunk,
                                          StringBuilder chatHistoryStringBuilder,
                                          StringBuilder aiResponseStringBuilder,
                                          List<ChatHistoryOriginal> originalChatHistoryList,
                                          Set<String> seenToolNames,
                                          AtomicBoolean interrupted) {
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
            case INTERRUPTED -> {
                // 中断哨兵：只置位，不返回任何内容（filter 会丢掉空串，前端看不到这一帧）
                interrupted.set(true);
                return "";
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

