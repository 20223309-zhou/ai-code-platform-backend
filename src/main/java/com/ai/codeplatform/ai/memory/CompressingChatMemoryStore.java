package com.ai.codeplatform.ai.memory;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话记忆存储装饰器：<b>写侧压缩 + 写入容错</b>。
 *
 * <h2>为什么需要压缩</h2>
 * langchain4j 的 {@code MessageWindowChatMemory} 是<b>无状态</b>的：每次 {@code add()} 都是
 * 「{@code store.getMessages} 读全量 → 追加一条 → {@code store.updateMessages} 全量写回」。
 * 而一轮代码生成里 98% 的消息是工具 request/result，其中 {@code readFile} / {@code readDir} /
 * {@code fetchWebPage} 的结果动辄上万字符，于是<b>每次</b>写回都是几百 KB 的 SETEX。
 * <p>
 * 实测重构一个老项目：冷启动回放 36 条历史（62K tokens）后，payload 基线就是 300KB 左右，
 * 之后每次工具结果入记忆都在这个基线上再加一份，最终在跨机 Redis 上撞到 Jedis 的
 * socket 读超时（2 秒，且该超时在 {@code RedisChatMemoryStore} 里写死，不受
 * {@code spring.data.redis.timeout} 影响）。
 *
 * <h2>压什么、不压什么</h2>
 * 只压「读」类工具的历史结果，并且<b>最近 keepFullResults 条保留全文</b>：
 * 正在改的那个文件需要模型能直接看到内容，压掉会让它反复 readFile，反而更慢更贵。
 * <p>
 * {@code id} 与 {@code toolName} 原样保留，只替换 {@code text} —— 丢掉消息或 id 会破坏
 * tool_calls 配对，模型下一轮直接报 400。
 *
 * <h2>压缩的代价（必须知情）</h2>
 * 记忆里没有常驻列表，模型每一轮都会重新 {@code getMessages} 读 Redis ——
 * <b>写进去什么，下一轮就看到什么</b>。所以压缩会改变模型可见的历史：它在冷启动恢复后
 * 只能看到「读过哪个文件、多少行」，看不到文件全文，需要时得重新调用工具。
 * 这是换取 payload 从 300KB 降到几十 KB 的必要代价。
 *
 * <h2>容错边界</h2>
 * <ul>
 *   <li><b>写</b>失败只记 WARN：内容此刻已经流式发给用户了，副本没落盘不该让整个请求以 500 结束；
 *       真正的真相源是 MySQL，下次冷启动会重新回放。</li>
 *   <li><b>读</b>失败照常抛出：{@code add()} 的实现是「先读全量、再追加、再全量写回」，
 *       读失败若被吞成空列表，紧接着那次写就会把历史全覆盖掉。宁可这次请求失败。</li>
 * </ul>
 */
@Slf4j
public class CompressingChatMemoryStore implements ChatMemoryStore {

    /**
     * 结果体积大、且读过一次基本就没用的工具。
     * 工具名 = {@code @Tool} 注解所在的方法名（注解里没指定 name 时以此为准）。
     */
    private static final Set<String> BULKY_READ_TOOLS = Set.of("readFile", "readDir", "fetchWebPage");

    /** 小于该字符数的结果不压（摘要本身也要几十字符，压了反而更长） */
    private static final int MIN_COMPRESS_CHARS = 800;

    /** 从工具入参 JSON 里捞目标路径 / URL，用于让摘要带上"读过什么" */
    private static final Pattern TARGET_PATTERN =
            Pattern.compile("\"(?:relativeFilePath|relativeDirPath|path|url)\"\\s*:\\s*\"([^\"]*)\"");

    /** 被委托的真实存储（Redis） */
    private final ChatMemoryStore delegate;

    /** 压缩开关，出问题可随时关掉 */
    private final boolean compressEnabled;

    /** 最近多少条读类工具结果保留全文（正在改的文件需要模型直接可见） */
    private final int keepFullResults;

    public CompressingChatMemoryStore(ChatMemoryStore delegate, boolean compressEnabled, int keepFullResults) {
        this.delegate = delegate;
        this.compressEnabled = compressEnabled;
        this.keepFullResults = Math.max(0, keepFullResults);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 读侧不压缩、不吞异常，理由见类注释
        return delegate.getMessages(memoryId);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        List<ChatMessage> toSave = compressEnabled ? compress(messages) : messages;
        if (log.isDebugEnabled() && toSave != messages) {
            log.debug("记忆写回压缩：{} -> {} 字符，{} 条消息，memoryId={}",
                    charCount(messages), charCount(toSave), toSave.size(), memoryId);
        }
        try {
            delegate.updateMessages(memoryId, toSave);
        } catch (Exception e) {
            // 内容已经流式交付给用户，副本落盘失败只降级为告警（下次冷启动会从 DB 重建）
            log.warn("对话记忆写入失败（已忽略，不影响本次响应），memoryId={}，条数={}",
                    memoryId, toSave.size(), e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try {
            delegate.deleteMessages(memoryId);
        } catch (Exception e) {
            log.warn("对话记忆删除失败（已忽略），memoryId={}", memoryId, e);
        }
    }

    /**
     * 把列表中"历史"的读类工具结果替换成摘要。
     * <p>
     * 不修改入参列表：调用方（{@code MessageWindowChatMemory}）传进来的可能是它自己构造的副本，
     * 但万一将来换成直接传内部列表，原地改会顺手污染模型上下文。
     */
    private List<ChatMessage> compress(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // 1. 从后往前标记最近 keepFullResults 条大结果，这些保留全文
        Set<Integer> keepIndexes = new HashSet<>();
        for (int i = messages.size() - 1; i >= 0 && keepIndexes.size() < keepFullResults; i--) {
            if (isBulkyResult(messages.get(i))) {
                keepIndexes.add(i);
            }
        }
        if (keepIndexes.size() == countBulky(messages)) {
            return messages;    // 没有需要压的，原样返回
        }

        // 2. 建立 tool_call id -> 入参 的映射，让摘要能带上文件路径
        Map<String, String> argumentsById = new HashMap<>();
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    argumentsById.put(request.id(), request.arguments());
                }
            }
        }

        // 3. 重建列表：只替换 text，id / toolName 原样保留
        List<ChatMessage> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (!keepIndexes.contains(i) && isBulkyResult(message)) {
                ToolExecutionResultMessage bulky = (ToolExecutionResultMessage) message;
                result.add(new ToolExecutionResultMessage(
                        bulky.id(),
                        bulky.toolName(),
                        summarize(bulky, argumentsById.get(bulky.id()))));
            } else {
                result.add(message);
            }
        }
        return result;
    }

    private boolean isBulkyResult(ChatMessage message) {
        if (!(message instanceof ToolExecutionResultMessage result)) {
            return false;
        }
        if (!BULKY_READ_TOOLS.contains(result.toolName())) {
            return false;
        }
        String text = result.text();
        return text != null && text.length() > MIN_COMPRESS_CHARS;
    }

    private int countBulky(List<ChatMessage> messages) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (isBulkyResult(message)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生成摘要：让模型知道"我读过什么、多大"，同时明确告知内容已省略、需要时可重新读。
     */
    private String summarize(ToolExecutionResultMessage message, String arguments) {
        String text = message.text() == null ? "" : message.text();
        long lines = text.lines().count();
        double kb = text.length() / 1024.0;
        String target = extractTarget(arguments);
        String action = switch (message.toolName()) {
            case "readDir" -> "已读取目录";
            case "fetchWebPage" -> "已抓取网页";
            default -> "已读取文件";
        };
        return String.format("[%s：%s（%d 行 / %.1f KB）。为控制上下文体积，历史内容已省略；"
                        + "如需查看最新内容，请重新调用 %s]",
                action, target, lines, kb, message.toolName());
    }

    /**
     * 从入参 JSON 里提取路径 / URL。用正则而非 JSON 解析：这里每轮会被调用几十次，
     * 而工具入参结构固定（只取第一个命中的键），正则足够且没有解析开销。
     */
    private String extractTarget(String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return "未知目标";
        }
        Matcher matcher = TARGET_PATTERN.matcher(arguments);
        if (matcher.find() && StrUtil.isNotBlank(matcher.group(1))) {
            return matcher.group(1);
        }
        return "未知目标";
    }

    private int charCount(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage result && result.text() != null) {
                total += result.text().length();
            } else if (message instanceof AiMessage aiMessage) {
                total += aiMessage.text() == null ? 0 : aiMessage.text().length();
            }
        }
        return total;
    }
}
