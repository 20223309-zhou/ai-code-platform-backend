package com.ai.codeplatform.model.enums;

import lombok.Getter;

/**
 * 积分流水类型。
 */
@Getter
public enum PointRecordTypeEnum {

    /** 每日签到 */
    SIGN_IN("SIGN_IN", "每日签到"),

    /** 兑换生成额度 */
    EXCHANGE("EXCHANGE", "兑换额度"),

    /** 管理员手动调整 */
    ADMIN_ADJUST("ADMIN_ADJUST", "管理员调整");

    private final String value;

    private final String label;

    PointRecordTypeEnum(String value, String label) {
        this.value = value;
        this.label = label;
    }
}
