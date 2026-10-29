package com.ai.codeplatform.model.dto.point;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 积分兑换请求。
 */
@Data
public class PointExchangeRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 商品标识，当前只有 QUOTA
     */
    private String goodsId;

    /**
     * 兑换份数，默认 1
     */
    private Integer count;
}
