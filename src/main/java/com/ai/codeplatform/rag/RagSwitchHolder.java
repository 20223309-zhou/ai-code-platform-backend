package com.ai.codeplatform.rag;

import org.springframework.stereotype.Component;

/**
 * RAG 请求上下文持有器（ThreadLocal）。
 * 除了「是否启用」，还要带上「当前应用的技术栈」：知识库里混存了多个项目的代码，
 * 若检索时不按类型过滤，React 项目会检索到 Vue 项目的代码当作"风格参考"，
 * 反而把 AI 带偏（生成出混入 Vue 语法的 JSX）。
 */
@Component
public class RagSwitchHolder {

    private static final ThreadLocal<Boolean> USE_RAG = new ThreadLocal<>();

    /**
     * 当前应用的技术栈标识，取值与向量库 metadata 的 project_type 一致：
     * {@code html} / {@code multi} / {@code vue} / {@code react}
     */
    private static final ThreadLocal<String> PROJECT_TYPE = new ThreadLocal<>();

    // 设置
    public static void set(boolean enabled) {
        USE_RAG.set(enabled);
    }

    // 获取
    public static boolean isEnabled() {
        return USE_RAG.get();
    }

    /**
     * 设置当前应用的技术栈。
     * 由 {@code AiCodeGeneratorFacade} 在进入生成分支前写入 —— 那里是唯一确定
     * codeGenType 的位置。不要改成"从用户提示词里猜 vue/react"：
     * 用户完全可以只说"做一个音乐播放器"而不提框架，猜出来的结果必然是错的。
     *
     * @param projectType html / multi / vue / react
     */
    public static void setProjectType(String projectType) {
        PROJECT_TYPE.set(projectType);
    }

    public static String getProjectType() {
        return PROJECT_TYPE.get();
    }

    // 清理（必须！否则线程池复用时会串到下一个请求）
    public static void clear() {
        USE_RAG.remove();
        PROJECT_TYPE.remove();
    }
}
