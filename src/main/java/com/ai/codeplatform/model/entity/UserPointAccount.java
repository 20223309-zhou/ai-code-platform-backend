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
 * 用户积分账户。
 * 每个用户一条，靠 userId 唯一索引保证。余额变动一律走原子 SQL
 * （balance = balance ± n），不要先查后写，否则并发下会算错。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("userPointAccount")
public class UserPointAccount implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("userId")
    private Long userId;

    /**
     * 当前积分余额
     */
    private Integer balance;

    /**
     * 累计获得积分（只增不减，用于展示"累计赚了多少"）
     */
    @Column("totalEarned")
    private Integer totalEarned;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;
}
