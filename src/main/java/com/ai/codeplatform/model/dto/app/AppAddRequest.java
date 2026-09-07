package com.ai.codeplatform.model.dto.app;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppAddRequest implements Serializable {

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 代码生成类型（创建时指定，创建后不可更改）。
     * 可选值：
     * - html：原生 HTML 模式
     * - multi_file：原生多文件模式
     * - vue_project：Vue 工程模式
     * - auto：智能选择（由 AI 根据 initPrompt 路由）
     * 不传或传 auto 时走 AI 路由；传具体类型时直接使用，不再路由。
     */
    private String codeGenType;

    private static final long serialVersionUID = 1L;
}
