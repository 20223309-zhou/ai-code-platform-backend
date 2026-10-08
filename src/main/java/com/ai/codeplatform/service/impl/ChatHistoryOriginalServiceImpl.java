package com.ai.codeplatform.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.ai.model.message.ToolExecutedMessage;
import com.ai.codeplatform.ai.model.message.ToolRequestMessage;
import com.ai.codeplatform.config.ChatHistoryReplayProperties;
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
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    /** 每条消息的结构开销（role、分隔符等），OpenAI 官方计数口径 */
    private static final int TOKEN_OVERHEAD_PER_MESSAGE = 7;

    /** 字符启发式的安全系数：吸收分词器与模型编码的差异 */
    private static final double CHAR_ESTIMATE_SAFETY = 1.25;

    /** 语义条目类型 */
    private static final int ITEM_USER = 0;
    private static final int ITEM_AI = 1;
    private static final int ITEM_TOOL_PAIR = 2;

    @Resource
    private ChatHistoryReplayProperties chatHistoryReplayProperties;

    /**
     * token 估算器。底层是 jtokkit 的 o200k 真 BPE 分词（经 langchain4j-open-ai 传递引入）。
     * 用 GPT-4o 的编码近似 DeepSeek 的分词，配合安全系数做预算足够；
     * 构造失败时为 null，退化为字符启发式。
     */
    private TokenCountEstimator tokenCountEstimator;

    @PostConstruct
    public void initTokenCountEstimator() {
        try {
            tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");
        } catch (Throwable t) {
            log.warn("初始化 TokenCountEstimator 失败，历史回放将退化为字符估算: {}", t.getMessage());
            tokenCountEstimator = null;
        }
    }

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
     * 加载原始对话历史到内存（推荐入口）。
     * <p>
     * 走配置的 token 预算策略，保证回放窗口内一定包含 user / ai 语义主线。
     *
     * @param appId      应用 ID
     * @param chatMemory 对话记忆
     * @return 加载成功的数量
     */
    @Override
    public int loadOriginalChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory) {
        return loadOriginalChatHistoryToMemory(appId, chatMemory, chatHistoryReplayProperties.getScanLimit());
    }

    /**
     * 加载原始对话历史到内存
     *
     * @param appId       应用 ID
     * @param chatMemory  对话记忆
     * @param scanLimit   扫描窗口上限（记录条数），与配置值取较小者
     * @return 加载成功的数量
     */
    @Override
    public int loadOriginalChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int scanLimit) {
        try {
            // 1. 按预算查询历史记录（返回 id 正序，且已保证工具调用成对、以 user 开头）
            List<ChatHistoryOriginal> originalHistoryList = queryHistoryByBudget(appId, scanLimit);
            if (CollUtil.isEmpty(originalHistoryList)) {
                return 0;
            }

            // 2. 先清理当前 app 的历史缓存，防止重复加载
            //    注意：clear() 内部会走 chatMemoryStore.deleteAll(appId)，把 Redis 里该应用的记忆清空
            chatMemory.clear();

            // 3. 遍历原始历史记录，根据类型将消息添加到记忆中
            int loadedCount = loadMessagesToMemory(originalHistoryList, chatMemory);

            log.info("成功为 appId: {} 加载 {} 条历史对话（估算 {} tokens）",
                    appId, loadedCount, tokensOf(originalHistoryList));
            return loadedCount;
        } catch (Exception e) {
            log.error("加载历史对话失败，appId: {}，error: {}", appId, e.getMessage(), e);
            // 加载失败不影响系统运行，只是没有历史上下文
            return 0;
        }
    }

    /**
     * 按 token 预算回放历史记录（方案 C：语义主线优先 + 工具细节按预算回填）。
     * <p>
     * 为什么不用"取最新 N 条"：实测一轮对话产生 106 条记录，其中 104 条是工具
     * request/result（占 98%），只有 1 条 user + 1 条 ai。取最新 20 条的结果就是
     * 10 组孤立的工具碎片，用户最初的需求永远读不回来 —— AI 表现为"隔一会儿就失忆"。
     * <p>
     * 本方法的优先级：
     * <ol>
     *   <li>P0：首轮 user（原始需求）无条件保留 —— 整场会话的语义锚点</li>
     *   <li>P1：最近 {@code keepUserAiRounds} 轮的 user / ai —— 极便宜但承载全部语义</li>
     *   <li>P2：最近 {@code keepToolRounds} 轮的完整工具记录对 —— 在剩余预算内从新往老回填</li>
     * </ol>
     * <p>
     * 工具调用报文合法性由分块配对保证（见下方实现），不再依赖旧实现的"边缘检查 +1"：
     * <ul>
     *   <li>孤立的 TOOL_EXECUTION_REQUEST（无结果）会被丢弃 —— 否则形成悬空 tool_calls，模型报 400</li>
     *   <li>孤立的 TOOL_EXECUTION_RESULT（请求在窗口外）会被丢弃 —— 否则 role 'tool' 无前置 tool_calls</li>
     *   <li>模型一次发出的并行 tool_calls（reqA,reqB,reqC,resA,resB,resC）<b>整批取舍</b>，
     *       不会出现只留 reqC 的情况</li>
     * </ul>
     *
     * @param appId     应用ID
     * @param scanLimit 扫描窗口上限（记录条数）
     * @return 历史记录列表，id 正序（老的在前）
     */
    private List<ChatHistoryOriginal> queryHistoryByBudget(Long appId, int scanLimit) {
        int limit = Math.min(Math.max(scanLimit, 1), chatHistoryReplayProperties.getScanLimit());

        // 1. 扫描窗口：按 id 倒序取回，再反转成正序。
        //    （用 id 而非时间戳排序：雪花 ID 严格递增，时间戳可能因相近值导致顺序不稳定）
        QueryWrapper scanWrapper = QueryWrapper.create()
                .eq(ChatHistoryOriginal::getAppId, appId)
                .orderBy(ChatHistoryOriginal::getId, false)
                .limit(0, limit + 1);
        List<ChatHistoryOriginal> scanned = this.list(scanWrapper);
        if (CollUtil.isEmpty(scanned) || scanned.size() <= 1) {
            return Collections.emptyList();
        }

        List<ChatHistoryOriginal> asc = new ArrayList<>(scanned);
        Collections.reverse(asc);   // 现在是 id ASC

        // 2. 最新一条若是本轮 user 消息，跳过 —— 它由本次请求本身携带，避免重复。
        //    若不是 user（例如上轮异常中断遗留的工具记录），保留，交给下面的配对逻辑处理。
        ChatHistoryOriginal newest = asc.remove(asc.size() - 1);
        if (typeOf(newest) != ChatHistoryMessageTypeEnum.USER) {
            asc.add(newest);
        }
        if (asc.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. 线性分块：连续的同类型记录归为一个块。
        //    并行 tool_calls 会写成 reqA,reqB,reqC,resA,resB,resC，因此块 = 一批。
        List<Block> blocks = new ArrayList<>();
        for (ChatHistoryOriginal record : asc) {
            ChatHistoryMessageTypeEnum type = typeOf(record);
            if (type == null) {
                log.error("未知消息类型，跳过该记录: {}", record.getMessageType());
                continue;
            }
            if (blocks.isEmpty() || blocks.get(blocks.size() - 1).type != type) {
                blocks.add(new Block(type));
            }
            blocks.get(blocks.size() - 1).records.add(record);
        }

        // 4. 归并为语义条目。孤儿 request / result 直接丢弃，从根上杜绝报文非法
        List<Item> items = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            Block block = blocks.get(i);
            if (block.type == ChatHistoryMessageTypeEnum.USER) {
                items.add(new Item(ITEM_USER, block.records));
                i++;
                continue;
            }
            if (block.type == ChatHistoryMessageTypeEnum.AI) {
                items.add(new Item(ITEM_AI, block.records));
                i++;
                continue;
            }
            if (block.type == ChatHistoryMessageTypeEnum.TOOL_EXECUTION_REQUEST) {
                Block next = (i + 1 < blocks.size()) ? blocks.get(i + 1) : null;
                boolean paired = next != null
                        && next.type == ChatHistoryMessageTypeEnum.TOOL_EXECUTION_RESULT
                        && next.records.size() == block.records.size();
                if (paired) {
                    // 完整的一批（支持并行 tool_calls），request + result 整体作为一个最小取舍单位
                    List<ChatHistoryOriginal> pair = new ArrayList<>(block.records);
                    pair.addAll(next.records);
                    items.add(new Item(ITEM_TOOL_PAIR, pair));
                    i += 2;
                } else {
                    // 孤立请求（没有对应结果，例如上一轮执行中断）：保留会形成悬空 tool_calls
                    log.debug("丢弃 {} 条无配对的工具请求记录", block.records.size());
                    i++;
                }
                continue;
            }
            // 孤立结果（其 request 落在窗口之外或被裁掉）：保留会导致 role 'tool' 无前置 tool_calls
            log.debug("丢弃 {} 条无配对的工具结果记录", block.records.size());
            i++;
        }
        if (items.isEmpty()) {
            return Collections.emptyList();
        }

        // 5. 轮次编号：以 user 消息为锚点
        int turn = -1;
        for (Item item : items) {
            if (item.kind == ITEM_USER) {
                turn++;
            }
            item.turnIndex = Math.max(turn, 0);
        }
        int totalTurns = turn + 1;

        int keepUserAiFromTurn = Math.max(0, totalTurns - chatHistoryReplayProperties.getKeepUserAiRounds());
        int keepToolFromTurn = Math.max(0, totalTurns - chatHistoryReplayProperties.getKeepToolRounds());

        // 6. 按优先级分类
        List<Item> keptUserAi = new ArrayList<>();
        List<Item> toolCandidates = new ArrayList<>();
        for (Item item : items) {
            if (item.kind == ITEM_USER || item.kind == ITEM_AI) {
                boolean firstUser = item.kind == ITEM_USER && item.turnIndex == 0;
                if (firstUser || item.turnIndex >= keepUserAiFromTurn) {
                    keptUserAi.add(item);
                }
            } else if (item.turnIndex >= keepToolFromTurn) {
                toolCandidates.add(item);
            }
        }
        for (Item item : keptUserAi) {
            item.tokens = tokensOf(item.records);
        }

        // 7. user/ai 自身就超预算的极端情况：从最老往新裁，首轮 user 保底
        int budget = chatHistoryReplayProperties.getReplayMaxTokens();
        int userAiTokens = keptUserAi.stream().mapToInt(it -> it.tokens).sum();
        int used = userAiTokens;
        if (userAiTokens > budget) {
            List<Item> trimmed = new ArrayList<>();
            int acc = 0;
            for (int k = keptUserAi.size() - 1; k >= 0; k--) {
                Item item = keptUserAi.get(k);
                boolean firstUser = item.kind == ITEM_USER && item.turnIndex == 0;
                if (!firstUser && acc + item.tokens > budget) {
                    break;
                }
                acc += item.tokens;
                trimmed.add(item);
            }
            Collections.reverse(trimmed);
            keptUserAi = trimmed;
            used = acc;
        }

        // 8. 剩余预算从新往老回填完整的工具记录对，一旦超预算立即停止
        List<Item> keptTools = new ArrayList<>();
        for (int k = toolCandidates.size() - 1; k >= 0; k--) {
            Item item = toolCandidates.get(k);
            int cost = tokensOf(item.records);
            if (used + cost > budget) {
                log.debug("历史回放达到 token 预算 {}，停止回填更早的工具记录", budget);
                break;
            }
            used += cost;
            keptTools.add(item);
        }
        Collections.reverse(keptTools);

        // 9. 合并并按 id 正序输出
        List<Item> selected = new ArrayList<>(keptUserAi);
        selected.addAll(keptTools);
        List<ChatHistoryOriginal> result = new ArrayList<>();
        for (Item item : selected) {
            result.addAll(item.records);
        }
        result.sort(Comparator.comparing(ChatHistoryOriginal::getId));

        // 10. 兜底：序列必须以 user 开头。
        //     首轮 user 已无条件保留，正常不会触发；仅当它落在扫描窗口之外时才截断。
        int start = 0;
        while (start < result.size() && typeOf(result.get(start)) != ChatHistoryMessageTypeEnum.USER) {
            start++;
        }
        if (start >= result.size()) {
            return Collections.emptyList();
        }
        if (start > 0) {
            log.debug("历史回放窗口头部存在 {} 条非 user 记录，已截断", start);
            result = new ArrayList<>(result.subList(start, result.size()));
        }
        // 11. 兜底：结尾不能停在孤立的 request 上（否则悬空 tool_calls）
        while (!result.isEmpty()
                && typeOf(result.get(result.size() - 1)) == ChatHistoryMessageTypeEnum.TOOL_EXECUTION_REQUEST) {
            result.remove(result.size() - 1);
        }

        log.debug("历史回放：扫描 {} 条 → 选中 {} 条（user/ai {} 项，工具 {} 项，约 {} tokens）",
                scanned.size(), result.size(), keptUserAi.size(), keptTools.size(), used);
        return result;
    }

    /**
     * 估算一批记录的 token 数（含每条消息的结构开销）
     */
    private int tokensOf(List<ChatHistoryOriginal> records) {
        int total = 0;
        for (ChatHistoryOriginal record : records) {
            total += estimateTokens(record.getMessage());
        }
        return total;
    }

    /**
     * 估算单条记录的 token 数。优先使用 jtokkit 真实 BPE 分词，失败则退化为字符启发式。
     */
    private int estimateTokens(String text) {
        if (StrUtil.isBlank(text)) {
            return TOKEN_OVERHEAD_PER_MESSAGE;
        }
        if (tokenCountEstimator != null) {
            try {
                return tokenCountEstimator.estimateTokenCountInText(text) + TOKEN_OVERHEAD_PER_MESSAGE;
            } catch (Exception e) {
                log.debug("分词器估算失败，退化为字符估算: {}", e.getMessage());
            }
        }
        return charHeuristic(text);
    }

    /**
     * 字符启发式估算：中文 1 字符 ≈ 0.6 token，其他 ≈ 0.3 token（DeepSeek 官方口径），
     * 再乘安全系数吸收不同模型编码差异。
     */
    private int charHeuristic(String text) {
        int chinese = 0;
        int other = 0;
        for (int idx = 0; idx < text.length(); idx++) {
            char c = text.charAt(idx);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chinese++;
            } else {
                other++;
            }
        }
        return (int) Math.ceil((chinese * 0.6 + other * 0.3 + TOKEN_OVERHEAD_PER_MESSAGE) * CHAR_ESTIMATE_SAFETY);
    }

    private ChatHistoryMessageTypeEnum typeOf(ChatHistoryOriginal record) {
        return record == null ? null : ChatHistoryMessageTypeEnum.getEnumByValue(record.getMessageType());
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
                    // 用户消息可能是多模态的（文本 + 图片），需要还原成 Content 列表，
                    // 否则图片会退化成一串 JSON 文本，模型看不到图
                    List<Content> contents = deserializeContents(history.getMessage());
                    if (contents.isEmpty()) {
                        chatMemory.add(UserMessage.from(history.getMessage()));
                    } else {
                        chatMemory.add(UserMessage.from(contents));
                    }
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

    /**
     * 将入库时序列化的多模态消息 JSON 还原为 Content 列表。
     * 序列化格式见 AppServiceImpl#chatToGenCode：
     * 文本 {"type":"text","text":"..."}、图片 {"type":"image","mimeType":"...","url":"..."}、
     * 文档 {"type":"md","text":"..."}
     *
     * @param message 数据库中的消息内容
     * @return 还原后的内容列表，非多模态 JSON 时返回空列表（调用方会退回纯文本处理）
     */
    private List<Content> deserializeContents(String message) {
        if (StrUtil.isBlank(message) || !JSONUtil.isJsonArray(message)) {
            return Collections.emptyList();
        }
        List<Content> contents = new ArrayList<>();
        for (Object item : JSONUtil.parseArray(message)) {
            if (!(item instanceof JSONObject contentMap)) {
                continue;
            }
            String type = contentMap.getStr("type");
            if ("image".equals(type)) {
                String url = contentMap.getStr("url");
                if (StrUtil.isNotBlank(url)) {
                    contents.add(new ImageContent(url));
                }
            } else {
                String text = contentMap.getStr("text");
                if (StrUtil.isNotBlank(text)) {
                    contents.add(new TextContent(text));
                }
            }
        }
        return contents;
    }

    /**
     * 连续的同类型记录块。模型一次发出并行 tool_calls 时会写成
     * reqA,reqB,reqC,resA,resB,resC，因此「块 = 一批」，是配对的最小单位。
     */
    private static class Block {
        final ChatHistoryMessageTypeEnum type;
        final List<ChatHistoryOriginal> records = new ArrayList<>();

        Block(ChatHistoryMessageTypeEnum type) {
            this.type = type;
        }
    }

    /**
     * 语义条目：一条 user / ai，或一整批配对的 request + result。
     * 取舍时以条目为最小单位，保证工具调用报文始终合法。
     */
    private static class Item {
        final int kind;
        final List<ChatHistoryOriginal> records;
        int tokens;
        int turnIndex;

        Item(int kind, List<ChatHistoryOriginal> records) {
            this.kind = kind;
            this.records = records;
        }
    }

}

