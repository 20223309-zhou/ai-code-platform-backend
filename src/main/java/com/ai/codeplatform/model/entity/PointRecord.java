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
import java.time.LocalDateTime;

/**
 * 积分流水。
 * <p>
 * 每次余额变动都要写一条，{@code balanceAfter} 是变动后的余额快照 ——
 * 用户对账、客服排查都靠它，不写的话只能靠累加所有历史记录反推。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("pointRecord")
public class PointRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    /**
     * 类型：SIGN_IN / EXCHANGE / ADMIN_ADJUST
     */
    private String type;

    /**
     * 变动数量：正数为获得，负数为消耗
     */
    private Integer amount;

    /**
     * 变动后的余额
     */
    @Column("balanceAfter")
    private Integer balanceAfter;

    private String remark;

    @Column("createTime")
    private LocalDateTime createTime;
}
