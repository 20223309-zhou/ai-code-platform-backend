package com.ai.codeplatform.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户签到记录。
 * {@code (userId, signDate)} 上的唯一索引是防重复签到的最终防线 ——
 * 并发请求下「先查今天有没有签过再插入」会同时通过检查，
 * 唯一索引能把第二个请求直接挡掉。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("userSignIn")
public class UserSignIn implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    /**
     * 签到日期（按 Asia/Shanghai 取，不要用服务器默认时区的 LocalDate.now()）
     */
    @Column("signDate")
    private LocalDate signDate;

    /**
     * 本次获得积分
     */
    @Column("rewardPoint")
    private Integer rewardPoint;

    @Column("createTime")
    private LocalDateTime createTime;
}
