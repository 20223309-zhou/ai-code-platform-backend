package com.ai.codeplatform.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.mapper.AppMapper;
import com.ai.codeplatform.mapper.SysOperationLogMapper;
import com.ai.codeplatform.mapper.UserMapper;
import com.ai.codeplatform.model.entity.App;
import com.ai.codeplatform.model.entity.SysOperationLog;
import com.ai.codeplatform.model.vo.StatisticsVO;
import com.ai.codeplatform.service.StatisticsService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 统计服务实现
 */
@Service
@Slf4j
public class StatisticsServiceImpl implements StatisticsService {

    private static final String STATISTICS_CACHE_KEY = "statistics:overview";
    private static final long CACHE_EXPIRE_HOURS = 30L;

    @Resource
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Resource
    private AppMapper appMapper;

    @Resource
    private SysOperationLogMapper sysOperationLogMapper;

    @Override
    public StatisticsVO getStatistics() {
        // 先从缓存获取
        String cachedString = redisTemplate.opsForValue().get(STATISTICS_CACHE_KEY);
        if (cachedString != null && !cachedString.isEmpty()) {
            StatisticsVO cachedStats = JSONUtil.toBean(cachedString, StatisticsVO.class);
            log.info("从缓存获取统计数据,cachedStats:{}", cachedStats.toString());
            return cachedStats;
        }

        // 缓存不存在，重新计算
        // 今日创作数量
        Long todayCount = countAppsByDateRange(getTodayStart(), LocalDateTime.now());

        // 本周创作数量
        Long weekCount = countAppsByDateRange(getWeekStart(), LocalDateTime.now());

        // 本月创作数量
        Long monthCount = countAppsByDateRange(getMonthStart(), LocalDateTime.now());

        // 总创作数量
        Long totalCount = countTotalApps();

        // 成功率统计
        Double successRate = calculateSuccessRate();

        // 平均耗时统计
        Double avgDurationMs = calculateAvgDuration();

        // 活跃用户统计（本周有创作的用户）
        Long activeUserCount = countActiveUsers(getWeekStart());

        // 总用户数
        Long totalUserCount = countTotalUsers();

        StatisticsVO statistics = StatisticsVO.builder()
                .todayCount(todayCount)
                .weekCount(weekCount)
                .monthCount(monthCount)
                .totalCount(totalCount)
                .successRate(successRate)
                .avgDurationMs(avgDurationMs)
                .activeUserCount(activeUserCount)
                .totalUserCount(totalUserCount)
                .build();
        String cachedJson = JSONUtil.toJsonStr(statistics);
        // 存入缓存，30 秒过期
        redisTemplate.opsForValue().set(STATISTICS_CACHE_KEY, cachedJson, CACHE_EXPIRE_HOURS, TimeUnit.SECONDS);
        log.info("统计数据已缓存，过期时间: {} 分钟", CACHE_EXPIRE_HOURS);

        return statistics;
    }

    /**
     * 统计指定时间范围内的应用数量
     */
    private Long countAppsByDateRange(LocalDateTime start, LocalDateTime end) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .ge("createTime", start)
                .le("createTime", end);
        return appMapper.selectCountByQuery(queryWrapper);
    }

    /**
     * 统计总App数量
     */
    private Long countTotalApps() {
        return sysOperationLogMapper.selectCountByQuery(QueryWrapper.create()
                .eq("operation","开始生成代码"));
    }

    /**
     * 计算生成成功率
     */
    private Double calculateSuccessRate() {
        Long totalCount = countTotalApps();
        if (totalCount == 0) {
            return 0.0;
        }

        QueryWrapper successWrapper = QueryWrapper.create()
                .eq("status", "SUCCESS")
                .eq("operation","开始生成代码");
        Long successCount = sysOperationLogMapper.selectCountByQuery(successWrapper);

        return (successCount.doubleValue() / totalCount.doubleValue()) * 100;
    }

    /**
     * 计算平均耗时（从创建到完成的平均时间）
     */
    private Double calculateAvgDuration() {
        // 查询所有已完成的app，计算 createTime 到 completedTime 的平均耗时
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("status", "SUCCESS")
                .eq("operation","开始生成代码");
        try {
            List<SysOperationLog> completeApps = sysOperationLogMapper.selectListByQuery(queryWrapper);
            if (completeApps == null || completeApps.isEmpty()) {
                return 0.0;
            }
            // 计算每个应用生成的耗时（毫秒）
            return completeApps.stream()
                    .mapToDouble(log -> log.getDurationMs().doubleValue())
                    .average()
                    .orElse(0.0);
        } catch (Exception e) {
            log.warn("计算平均耗时失败", e);
        }
        return 0.0;
    }
    /**
     * 统计活跃用户数（本周有创作的用户）
     */
    private Long countActiveUsers(LocalDateTime weekStart) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .ge("createTime", weekStart);

        try {
            List<App> articles = appMapper.selectListByQuery(queryWrapper);
            // 统计去重后的用户数
            return articles.stream()
                    .map(App::getUserId)
                    .distinct()
                    .count();
        } catch (Exception e) {
            log.warn("统计活跃用户失败", e);
        }
        return 0L;
    }

    /**
     * 统计总用户数
     */
    private Long countTotalUsers() {
        return userMapper.selectCountByQuery(QueryWrapper.create());
    }
    /**
     * 获取今天开始时间
     */
    private LocalDateTime getTodayStart() {
        return LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
    }

    /**
     * 获取本周开始时间（周一）
     */
    private LocalDateTime getWeekStart() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1);
        return LocalDateTime.of(monday, LocalTime.MIN);
    }

    /**
     * 获取本月开始时间
     */
    private LocalDateTime getMonthStart() {
        LocalDate today = LocalDate.now();
        LocalDate firstDay = today.withDayOfMonth(1);
        return LocalDateTime.of(firstDay, LocalTime.MIN);
    }

}
