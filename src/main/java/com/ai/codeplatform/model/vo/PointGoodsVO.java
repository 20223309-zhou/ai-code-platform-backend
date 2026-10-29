package com.ai.codeplatform.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 积分商城商品。
 * <p>
 * 目前只有「生成额度」一个商品，先写死在服务端常量里 ——
 * 商品种类多了再考虑建表，现在建表只是多一层维护成本。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointGoodsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商品标识，兑换时回传 */
    private String id;

    private String name;

    private String description;

    /** 单份消耗积分 */
    private Integer cost;

    /** 单次最多可兑换份数 */
    private Integer maxCount;
}
