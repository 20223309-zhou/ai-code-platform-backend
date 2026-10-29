package com.ai.codeplatform.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 兑换结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 本次消耗积分 */
    private Integer cost;

    /** 兑换到的额度次数 */
    private Integer gainedQuota;

    /** 兑换后的积分余额 */
    private Integer balance;

    /** 兑换后的剩余生成额度 */
    private Integer quota;
}
