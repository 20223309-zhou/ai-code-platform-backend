package com.ai.codeplatform.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 签到结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignInResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本次获得积分 */
    private Integer reward;

    /** 签到后的积分余额 */
    private Integer balance;

    /** 连续签到天数 */
    private Integer continuousDays;
}
