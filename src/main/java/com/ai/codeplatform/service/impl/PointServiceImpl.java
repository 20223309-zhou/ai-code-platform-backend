package com.ai.codeplatform.service.impl;

import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.mapper.PointRecordMapper;
import com.ai.codeplatform.mapper.UserPointAccountMapper;
import com.ai.codeplatform.mapper.UserSignInMapper;
import com.ai.codeplatform.model.entity.PointRecord;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.model.entity.UserPointAccount;
import com.ai.codeplatform.model.entity.UserSignIn;
import com.ai.codeplatform.model.enums.PointRecordTypeEnum;
import com.ai.codeplatform.model.vo.ExchangeResultVO;
import com.ai.codeplatform.model.vo.PointAccountVO;
import com.ai.codeplatform.model.vo.PointGoodsVO;
import com.ai.codeplatform.model.vo.SignInResultVO;
import com.ai.codeplatform.service.PointService;
import com.ai.codeplatform.service.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 签到与积分服务实现。
 * <p>
 * ⚠️ 这里所有余额变动都遵循同一条规则：**先锁行读准余额，再改，余额在内存里算**。
 * 绝不能「update 完再 select 出来记流水」—— 那种写法在本项目里必然记错：
 * 积分是读写分离 + 短事务，update 之后的 select 要么命中 MyBatis 一级缓存，
 * 要么被路由到从库拿到本次变更之前的快照，记下来的 balanceAfter 就是上一次的余额。
 */
@Slf4j
@Service
public class PointServiceImpl implements PointService {

    /**
     * 签到按北京时区算日期。
     * <p>
     * 服务器若跑在 UTC，用 {@code LocalDate.now()} 会让早上 8 点前的签到
     * 算到前一天，用户会觉得"我明明签了还显示没签"。
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每日签到奖励 */
    private static final int SIGN_IN_REWARD = 1;

    /** 单次最多兑换份数，防手滑/脚本狂点 */
    private static final int MAX_EXCHANGE_COUNT = 10;

    /** 当前唯一的商品：生成额度 */
    private static final String GOODS_QUOTA = "QUOTA";

    /** 每 1 积分兑换 1 次生成额度 */
    private static final int COST_PER_QUOTA = 1;

    @Resource
    private UserPointAccountMapper accountMapper;

    @Resource
    private UserSignInMapper signInMapper;

    @Resource
    private PointRecordMapper recordMapper;

    @Resource
    private UserService userService;

    @Override
    public PointAccountVO getAccount(Long userId) {
        LocalDate today = LocalDate.now(ZONE);
        UserPointAccount account = selectAccount(userId);
        boolean signedToday = isSigned(userId, today);
        return PointAccountVO.builder()
                .balance(account == null ? 0 : account.getBalance())
                .totalEarned(account == null ? 0 : account.getTotalEarned())
                .signedToday(signedToday)
                .continuousDays(continuousDays(userId, today, signedToday))
                .monthSignedDays(monthSignedDays(userId, today, signedToday))
                .quota(currentQuota(userId))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SignInResultVO signIn(Long userId) {
        LocalDate today = LocalDate.now(ZONE);

        // 常规路径先给个友好提示，真正的防重靠下面的唯一索引
        if (isSigned(userId, today)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "今天已经签到过了，明天再来");
        }

        try {
            signInMapper.insert(UserSignIn.builder()
                    .userId(userId)
                    .signDate(today)
                    .rewardPoint(SIGN_IN_REWARD)
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            // 并发下第二个请求会撞唯一索引 (userId, signDate)
            if (isDuplicateKey(e)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "今天已经签到过了，明天再来");
            }
            throw e;
        }

        ensureAccount(userId);

        // 加锁读准余额，再改，再在内存里算 —— 不做二次查询
        UserPointAccount locked = accountMapper.selectForUpdate(userId);
        int balanceBefore = locked == null ? 0 : locked.getBalance();
        accountMapper.increase(userId, SIGN_IN_REWARD);
        int balanceAfter = balanceBefore + SIGN_IN_REWARD;

        writeRecord(userId, PointRecordTypeEnum.SIGN_IN, SIGN_IN_REWARD, balanceAfter, "每日签到");

        return SignInResultVO.builder()
                .reward(SIGN_IN_REWARD)
                .balance(balanceAfter)
                // 刚插入的签到记录从库可能还没同步，显式告知"今天已签"
                .continuousDays(continuousDays(userId, today, true))
                .build();
    }

    @Override
    public List<PointGoodsVO> listGoods() {
        return List.of(PointGoodsVO.builder()
                .id(GOODS_QUOTA)
                .name("生成额度")
                .description("1 积分兑换 1 次应用生成额度，兑换后立即生效")
                .cost(COST_PER_QUOTA)
                .maxCount(MAX_EXCHANGE_COUNT)
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExchangeResultVO exchange(Long userId, String goodsId, int count) {
        if (!GOODS_QUOTA.equals(goodsId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "商品不存在");
        }
        if (count <= 0 || count > MAX_EXCHANGE_COUNT) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "单次最多兑换 " + MAX_EXCHANGE_COUNT + " 份");
        }

        int cost = count * COST_PER_QUOTA;
        ensureAccount(userId);

        UserPointAccount locked = accountMapper.selectForUpdate(userId);
        int balanceBefore = locked == null ? 0 : locked.getBalance();
        if (balanceBefore < cost) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分不足");
        }

        // 兜底：并发下如果余额被别的请求改到不足，这里影响行数为 0
        int affected = accountMapper.decrease(userId, cost);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分不足");
        }

        int balanceAfter = balanceBefore - cost;

        // 兑换前的额度同样是"改之前读"，返回给前端的值才准（前端随后还会刷一次用户信息）
        User user = userService.getById(userId);
        int quotaBefore = user == null ? 0 : user.getQuota();

        // 加生成额度。用 setRaw 走原子 SQL，不能用 set(读出来的值 + n)
        boolean added = userService.updateChain()
                .setRaw(User::getQuota, "quota + " + count)
                .where(User::getId).eq(userId)
                .update();
        if (!added) {
            // 回滚上面扣掉的积分
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "额度发放失败，请稍后重试");
        }

        writeRecord(userId, PointRecordTypeEnum.EXCHANGE, -cost, balanceAfter,
                "兑换 " + count + " 次生成额度");

        return ExchangeResultVO.builder()
                .cost(cost)
                .gainedQuota(count)
                .balance(balanceAfter)
                .quota(quotaBefore + count)
                .build();
    }

    @Override
    public Page<PointRecord> pageRecords(Long userId, int pageNum, int pageSize) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .orderBy("createTime desc");
        return recordMapper.paginate(Page.of(pageNum, pageSize), queryWrapper);
    }

    // ==================== 内部方法 ====================

    private UserPointAccount selectAccount(Long userId) {
        return accountMapper.selectOneByQuery(QueryWrapper.create().eq("userId", userId));
    }

    /**
     * 账户不存在时补一条。
     * 并发签到可能同时走到这里，唯一索引 uk_userId 会挡住后一个，
     * 捕获后忽略即可 —— 账户存在是幂等的。
     */
    private void ensureAccount(Long userId) {
        if (selectAccount(userId) != null) {
            return;
        }
        try {
            accountMapper.insert(UserPointAccount.builder()
                    .userId(userId)
                    .balance(0)
                    .totalEarned(0)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
        }
    }

    private boolean isSigned(Long userId, LocalDate date) {
        return signInMapper.selectCountByQuery(
                QueryWrapper.create().eq("userId", userId).eq("signDate", date)) > 0;
    }

    /**
     * 连续签到天数。
     * 今天没签时从昨天开始往前数 —— 展示"已连续 N 天"仍然成立，
     * 不能因为今天还没签就显示 0，否则用户会以为断了。
     *
     * @param assumeSignedToday 刚签完时传 true：签到记录可能还没同步到从库，
     *                          这里直接把今天算进去，否则用户会看到"连续天数没变"
     */
    private int continuousDays(Long userId, LocalDate today, boolean assumeSignedToday) {
        List<UserSignIn> recent = signInMapper.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .and("signDate >= ?", today.minusDays(90))
                .orderBy("signDate desc"));

        Set<LocalDate> dates = recent.stream()
                .map(UserSignIn::getSignDate)
                .collect(Collectors.toSet());
        if (assumeSignedToday) {
            dates.add(today);
        }

        LocalDate cursor = dates.contains(today) ? today : today.minusDays(1);
        int days = 0;
        while (dates.contains(cursor)) {
            days++;
            cursor = cursor.minusDays(1);
        }
        return days;
    }

    /** 本月已签到的日期（1-31），前端画签到日历用 */
    private List<Integer> monthSignedDays(Long userId, LocalDate today, boolean assumeSignedToday) {
        LocalDate firstDay = today.withDayOfMonth(1);
        Set<Integer> days = signInMapper.selectListByQuery(QueryWrapper.create()
                        .eq("userId", userId)
                        .and("signDate >= ?", firstDay)
                        .and("signDate <= ?", today))
                .stream()
                .map(item -> item.getSignDate().getDayOfMonth())
                .collect(Collectors.toCollection(TreeSet::new));
        if (assumeSignedToday) {
            days.add(today.getDayOfMonth());
        }
        return new ArrayList<>(days);
    }

    private int currentQuota(Long userId) {
        User user = userService.getById(userId);
        return user == null ? 0 : user.getQuota();
    }

    private void writeRecord(Long userId, PointRecordTypeEnum type, int amount,
                             int balanceAfter, String remark) {
        recordMapper.insert(PointRecord.builder()
                .userId(userId)
                .type(type.getValue())
                .amount(amount)
                .balanceAfter(balanceAfter)
                .remark(remark)
                .createTime(LocalDateTime.now())
                .build());
    }

    private boolean isDuplicateKey(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof DuplicateKeyException) {
                return true;
            }
            String message = cursor.getMessage();
            // ShardingSphere 会把原始 SQLException 包一层，消息里仍带 Duplicate entry
            if (message != null && message.contains("Duplicate entry")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
