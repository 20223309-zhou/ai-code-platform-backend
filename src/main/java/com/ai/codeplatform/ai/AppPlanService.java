package com.ai.codeplatform.ai;

import com.ai.codeplatform.ai.model.plan.PlanListResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 方案预选服务。
 * <p>
 * 刻意独立于 {@link AiCodeGeneratorService}：
 * <ul>
 *   <li>不挂任何工具 —— 否则模型拿到 prompt 就会去写文件，方案阶段不应该产生任何副作用</li>
 *   <li>不挂 ChatMemory —— 方案与后续的代码会话无关，写进记忆只会污染上下文</li>
 *   <li>不挂 RAG —— 方案是设计决策，不需要知识库检索</li>
 * </ul>
 * 因此它是一次纯文本 + 结构化输出的轻量调用，用便宜的 routing 模型即可。
 */
public interface AppPlanService {

    /**
     * 根据用户的初始描述生成若干设计方案。
     *
     * @param userPrompt 用户的自然语言描述
     * @return 方案列表（含是否建议跳过选择的标记）
     */
    @SystemMessage(fromResource = "prompt/codegen-plan-system-prompt.txt")
    PlanListResult generatePlans(@UserMessage String userPrompt);
}
