package com.ai.codeplatform.ai.model.plan;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 一个可选的界面设计方案。
 * 字段上的 {@link Description} 会被 langchain4j 转成 JSON Schema 的描述，
 * 模型据此生成结构化输出；描述写得越具体，模型编造格式的概率越低。
 */
@Description("一个可选的界面设计方案")
@Data
public class AppPlan implements Serializable {

    @Description("方案标识，单个大写字母，如 A、B、C")
    private String id;

    @Description("方案标题，不超过 12 个字，体现布局形态，如「仪表盘式」「卡片流式」「分步向导」")
    private String title;

    @Description("视觉风格，如「极简浅色」「深色科技」「柔和奶油」")
    private String style;

    @Description("布局结构描述，如「左侧边栏导航 + 右侧内容区 + 顶部统计卡」")
    private String layout;

    @Description("一句话说明适合什么场景，不超过 30 字")
    private String desc;

    @Description("3 个以内的标签，如 [\"数据密集\",\"桌面优先\"]")
    private List<String> tags;

    @Description("是否为推荐方案；多个方案中只能有一个为 true")
    private Boolean recommended;

    private static final long serialVersionUID = 1L;
}
