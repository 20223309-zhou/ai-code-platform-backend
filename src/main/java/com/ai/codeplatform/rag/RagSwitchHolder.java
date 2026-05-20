package com.ai.codeplatform.rag;

import org.springframework.stereotype.Component;

@Component
public class RagSwitchHolder {

    private static final ThreadLocal<Boolean> USE_RAG = new ThreadLocal<>();

    // 设置
    public static void set(boolean enabled) {
        USE_RAG.set(enabled);
    }

    // 获取
    public static boolean isEnabled() {
        return USE_RAG.get();
    }

    // 清理（必须！否则内存泄漏）
    public static void clear() {
        USE_RAG.remove();
    }
}