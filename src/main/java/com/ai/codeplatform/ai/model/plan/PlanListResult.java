package com.ai.codeplatform.ai.model.plan;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 方案预选接口的结构化返回。
 * <p>
 * 用于「创建应用之前先确认设计方向」这一环节：模型给出若干在布局/交互上有实质差异的方案，
 * 用户选择（或自定义）之后，选择结果会被拼进 prompt，再走原有的生成链路。
 */
@Description("设计方案列表")
@Data
public class PlanListResult implements Serializable {

    @Description("2~3 个在布局结构或交互模式上有实质差异的方案；需求已足够明确时只返回 1 个")
    private List<AppPlan> plans;

    @Description("需求是否已足够明确、无需用户做设计决策。为 true 时 plans 只放 1 个推荐方案")
    private Boolean recommendSkip;

    @Description("用户描述是否与构建前端页面无关（例如写脚本、算法题、搭建后端接口、写 SQL、数据分析）")
    private Boolean irrelevant;

    @Description("当 irrelevant 为 true 时给用户的一句引导语，说明该描述看起来不是前端页面，不超过 40 字")
    private String hint;

    private static final long serialVersionUID = 1L;
}
