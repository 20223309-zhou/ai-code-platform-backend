package com.ai.codeplatform.ai.model.message;

import lombok.Getter;

/**
 * 流式消息类型枚举
 */
@Getter
public enum StreamMessageTypeEnum {

    AI_RESPONSE("ai_response", "AI响应"),
    THINKING("thinking", "思考过程"),
    TOOL_REQUEST("tool_request", "工具请求"),
    TOOL_EXECUTED("tool_executed", "工具执行结果"),
    /**
     * 中断哨兵：服务端主动取消时由 Facade 在 complete 之前发出，不展示给前端。
     * 没有它，下游收到的「取消」和「正常写完」完全一样，无法落库打标记、也没法补对话记忆。
     */
    INTERRUPTED("interrupted", "生成被中断");

    private final String value;
    private final String text;

    StreamMessageTypeEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    /**
     * 根据值获取枚举
     */
    public static StreamMessageTypeEnum getEnumByValue(String value) {
        for (StreamMessageTypeEnum typeEnum : values()) {
            if (typeEnum.getValue().equals(value)) {
                return typeEnum;
            }
        }
        return null;
    }
}
