package com.ai.codeplatform.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

@Getter
public enum VipEnum {
    NORMAL("普通用户", "0"),
    VIP("vip用户", "1"),
    SVIP("超级vip用户", "2");

    private final String text;

    private final String value;

    VipEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static VipEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (VipEnum anEnum : VipEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
