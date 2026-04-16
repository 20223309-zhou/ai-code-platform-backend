package com.ai.codeplatform.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum ChatHistoryMessageTypeEnum {

    USER("用户", "user"),
    AI("AI", "ai"),
    TOOL_EXECUTION_REQUEST("工具调用请求", "toolExecutionRequest"),
    TOOL_EXECUTION_RESULT("工具调用结果", "toolExecutionResult");

    private final String text;

    private final String value;

    ChatHistoryMessageTypeEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static ChatHistoryMessageTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (ChatHistoryMessageTypeEnum anEnum : ChatHistoryMessageTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}

