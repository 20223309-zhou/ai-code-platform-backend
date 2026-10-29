package com.ai.codeplatform.mapper;

import com.ai.codeplatform.model.entity.UserPointAccount;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 积分账户 映射层。
 * <p>
 * 加减积分用自定义 UPDATE 而不是「先 select 再 set」：
 * 余额变动必须是一条原子 SQL，否则并发签到/兑换会把余额算错。
 */
public interface UserPointAccountMapper extends BaseMapper<UserPointAccount> {

    /**
     * 加锁读取账户（SELECT ... FOR UPDATE）。
     * <p>
     * 用来解决「update 完立刻 select 读到旧值」：事务内先锁行读准余额，
     * 后续余额变动在内存里算，不再查第二次。
     * FOR UPDATE 是当前读，会绕过 MVCC 快照，拿到的必然是已提交的最新值。
     */
    @Select("select * from userPointAccount where userId = #{userId} for update")
    UserPointAccount selectForUpdate(@Param("userId") Long userId);

    /**
     * 加积分（签到等）
     */
    @Update("update userPointAccount set balance = balance + #{amount}, "
            + "totalEarned = totalEarned + #{amount}, updateTime = now() "
            + "where userId = #{userId}")
    int increase(@Param("userId") Long userId, @Param("amount") int amount);

    /**
     * 扣积分（兑换）。
     * <p>
     * where 里带 {@code balance >= amount}：余额不足时影响行数为 0，
     * 调用方据此判断"积分不足"，不会出现扣成负数。
     */
    @Update("update userPointAccount set balance = balance - #{amount}, updateTime = now() "
            + "where userId = #{userId} and balance >= #{amount}")
    int decrease(@Param("userId") Long userId, @Param("amount") int amount);
}
