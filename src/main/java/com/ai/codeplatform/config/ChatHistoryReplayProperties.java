package com.ai.codeplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话历史回放（冷启动重建对话记忆）的预算配置。
 * 背景：Caffeine 记忆缓存过期（30 分钟无访问）后，需要从 MySQL 重新加载历史。
 * 原实现是"取最新 N 条"，而一轮对话里 98% 的记录是工具 request/result，
 * 导致回放窗口里连一条 user 消息都没有 —— 语义主线整个丢失。
 * 因此改为按 token 预算 + 优先级回填
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.chat-history")
public class ChatHistoryReplayProperties {

    /**
     * 历史回放的总 token 预算。1M 上下文窗口里给历史 64K（约 6%）绰绰有余；
     * 再大会拖慢 prefill（首字延迟随输入长度线性增长）并推高成本。
     */
    private int replayMaxTokens = 64000;

    /**
     * 单次扫描的最大记录数（按 id 倒序）。防止超长会话全表扫描。
     */
    private int scanLimit = 400;

    /**
     * user / ai 记录保留最近多少轮。这类记录极便宜（实测 10 轮约 5K 字符），却是语义主线的全部，给足轮次。
     */
    private int keepUserAiRounds = 10;

    /**
     * 完整的工具 request/result 记录保留最近多少轮。
     * 工具细节价值随轮次快速衰减（第 3 轮之前 readDir 返回了什么，对当前轮毫无用处），
     * 且它是 token 消耗的大头，所以要单独限制。
     */
    private int keepToolRounds = 3;
}
