package com.ai.codeplatform.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统计数据 VO
 *
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 今日创作数量
     */
    private Long todayCount;

    /**
     * 本周创作数量
     */
    private Long weekCount;

    /**
     * 本月创作数量
     */
    private Long monthCount;

    /**
     * 总创作数量
     */
    private Long totalCount;

    /**
     * 成功率（百分比）
     */
    private Double successRate;

    /**
     * 平均耗时（毫秒）
     */
    private Double avgDurationMs;

    /**
     * 活跃用户数（本周）
     */
    private Long activeUserCount;

    /**
     * 总用户数
     */
    private Long totalUserCount;
}
