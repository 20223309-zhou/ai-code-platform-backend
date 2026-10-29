package com.ai.codeplatform.service;

import com.ai.codeplatform.model.entity.PointRecord;
import com.ai.codeplatform.model.vo.ExchangeResultVO;
import com.ai.codeplatform.model.vo.PointAccountVO;
import com.ai.codeplatform.model.vo.PointGoodsVO;
import com.ai.codeplatform.model.vo.SignInResultVO;
import com.mybatisflex.core.paginate.Page;

import java.util.List;

/**
 * 签到与积分服务。
 */
public interface PointService {

    /**
     * 账户概览：余额、累计获得、今日是否签到、连续天数、本月签到日、剩余额度
     */
    PointAccountVO getAccount(Long userId);

    /**
     * 每日签到。
     * 一天只能签一次，靠 {@code (userId, signDate)} 唯一索引兜底，
     * 并发请求只有一个能成功。
     *
     * @return 本次获得的积分与签到后的余额
     */
    SignInResultVO signIn(Long userId);

    /**
     * 积分商城商品列表（当前只有「生成额度」一项）
     */
    List<PointGoodsVO> listGoods();

    /**
     * 兑换。
     * 扣积分与加额度在同一事务里，任何一步失败都整体回滚 ——
     * 不能出现"积分扣了额度没加"。
     *
     * @param goodsId 商品标识
     * @param count   兑换份数
     */
    ExchangeResultVO exchange(Long userId, String goodsId, int count);

    /**
     * 积分流水分页
     */
    Page<PointRecord> pageRecords(Long userId, int pageNum, int pageSize);
}
