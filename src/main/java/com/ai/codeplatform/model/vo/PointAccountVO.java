package com.ai.codeplatform.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 积分账户概览（个人中心一次拉全，避免前端连开三个接口）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointAccountVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前积分余额 */
    private Integer balance;

    /** 累计获得积分 */
    private Integer totalEarned;

    /** 今天是否已签到 */
    private Boolean signedToday;

    /**
     * 连续签到天数。
     * 今天没签时展示的是到昨天为止的连续数，不会因为"今天还没签"就归零。
     */
    private Integer continuousDays;

    /** 本月已签到的"日"（1-31），前端用于画签到日历 */
    private List<Integer> monthSignedDays;

    /** 当前剩余生成额度，和积分一起展示更直观 */
    private Integer quota;
}
