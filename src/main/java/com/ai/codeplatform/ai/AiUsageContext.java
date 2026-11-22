package com.ai.codeplatform.ai;

import dev.langchain4j.model.output.TokenUsage;

/**
 * 一次 AI 生成请求的用量快照。
 * 为什么需要这个类：token 用量只有 {@code AiCodeGeneratorFacade} 在流结束回调里能拿到，
 * 而写日志的是 {@code LogInterceptor}（对 Flux 接口在 doOnComplete 时落库）。
 * 两者之间没有调用关系，只能靠一个同线程的信使传递。
 * 为什么用 ThreadLocal 是安全的：facade 在 {@code onCompleteResponse} 里把值放进来，
 * 紧接着调用 {@code sink.complete()}，下游的 doOnComplete（日志切面）是**同一次调用栈**上同步执行的，
 * 之后立刻 poll 并 remove。不会跨线程，也不会残留到线程池的下一个任务。
 */
public final class AiUsageContext {

    private static final ThreadLocal<Usage> HOLDER = new ThreadLocal<>();

    private AiUsageContext() {
    }

    /**
     * 记录本轮生成的完整用量。usage 为 null 时不做任何事（部分服务商不返回 usage）。
     */
    public static void set(Long appId, String modelName, TokenUsage tokenUsage, int modelRounds) {
        set(appId, modelName, tokenUsage, modelRounds, false);
    }

    /**
     * 记录「被用户中断」时的部分用量：模型没有返回最终 usage，
     * 但这些值已经是前面各轮真实消耗掉的，记下来比留空更有价值。
     */
    public static void setPartial(Long appId, String modelName, TokenUsage tokenUsage, int modelRounds) {
        set(appId, modelName, tokenUsage, modelRounds, true);
    }

    private static void set(Long appId, String modelName, TokenUsage tokenUsage,
                            int modelRounds, boolean partial) {
        if (tokenUsage == null) {
            return;
        }
        HOLDER.set(new Usage(appId, modelName,
                tokenUsage.inputTokenCount(),
                tokenUsage.outputTokenCount(),
                tokenUsage.totalTokenCount(),
                modelRounds,
                partial));
    }

    /**
     * 取出并清空。必须 poll 而不是 get，否则线程复用时会把上一次的用量带给下一个请求。
     */
    public static Usage poll() {
        Usage usage = HOLDER.get();
        HOLDER.remove();
        return usage;
    }

    /**
     * 用量快照。
     *
     * @param appId        应用 ID，用于在校验时防止跨请求串用
     * @param modelName    本次使用的模型名
     * @param inputTokens  提示词侧 token
     * @param outputTokens 生成侧 token
     * @param totalTokens  合计 token
     * @param modelRounds  模型调用轮次（1 表示单轮、没有工具调用）
     * @param partial      是否为中断时的部分用量（此时没有最后一轮的 usage）
     */
    public record Usage(Long appId, String modelName, Integer inputTokens, Integer outputTokens,
                        Integer totalTokens, int modelRounds, boolean partial) {
    }
}
