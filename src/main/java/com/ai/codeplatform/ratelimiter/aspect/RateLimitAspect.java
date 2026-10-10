package com.ai.codeplatform.ratelimiter.aspect;

import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.ratelimiter.annotation.RateLimit;
import com.ai.codeplatform.service.UserService;
import com.ai.codeplatform.utils.IpUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 限流切面
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private UserService userService;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        // 获取限流key
        String key = generateRateLimitKey(point, rateLimit);
        try {
            // 使用Redisson的分布式限流器，并为限流器设置key
            RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
            // 设置限流器参数：每个时间窗口允许的请求数和时间窗口
            // trySetRate 是 "不存在才设置" ,只有首次创建该 key 的限流器时会写入速率配置，后续调用直接跳过
            rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), Duration.ofSeconds(rateLimit.rateInterval()), Duration.ofHours(1));
            // 尝试获取令牌，如果获取失败则限流
            if (!rateLimiter.tryAcquire(1)) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
            }
        } catch (BusinessException e) {
            // 真正命中限流，原样抛出
            throw e;
        } catch (Exception e) {
            // fail-open：限流器自身不可用（Redis 抖动 / 连接拒绝）时放行，不要让限流组件拖垮业务。
            // 登录接口依赖本切面，若此处抛异常会导致所有人无法登录——宁可不限流，也不能不可用。
            log.error("限流器不可用，本次请求放行, key: {}", key, e);
        }
    }

    private String generateRateLimitKey(JoinPoint point, RateLimit rateLimit) {
        // rateLimiter key
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append("rate_limit:");
        // 添加自定义前缀
        if (!rateLimit.key().isEmpty()) {
            keyBuilder.append(rateLimit.key()).append(":");
        }
        // 根据限流类型生成不同的key
        switch (rateLimit.limitType()) {
            case API:
                // 接口级别：方法名
                MethodSignature signature = (MethodSignature) point.getSignature();
                Method method = signature.getMethod();
                keyBuilder.append("api:").append(method.getDeclaringClass().getSimpleName())
                        .append(".").append(method.getName());
                break;
            case USER:
                // 用户级别：用户ID
                try {
                    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attributes != null) {
                        // 获取当前登录用户
                        HttpServletRequest request = attributes.getRequest();
                        User loginUser = userService.getLoginUser(request);
                        // 拼接限流器key rate_limit:user:{userId}
                        keyBuilder.append("user:").append(loginUser.getId());
                    } else {
                        // 无法获取请求上下文，使用IP限流
                        keyBuilder.append("ip:").append(getClientIP());
                    }
                } catch (BusinessException e) {
                    // 未登录用户使用IP限流
                    keyBuilder.append("ip:").append(getClientIP());
                }
                break;
            case IP:
                // IP级别：客户端IP
                keyBuilder.append("ip:").append(getClientIP());
                break;
            default:
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的限流类型");
        }
        return keyBuilder.toString();
    }

    private String getClientIP() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return IpUtils.getClientIp(attributes == null ? null : attributes.getRequest());
    }

}
