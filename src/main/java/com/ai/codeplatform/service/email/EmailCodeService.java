package com.ai.codeplatform.service.email;

import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.config.EmailProperties;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.utils.EmailUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;

/**
 * 邮箱验证码：Redis 存取 + 发送冷却 + 每日配额 + 校验失败计数。
 * <p>
 * Redis key 约定（沿用项目里 {@code captcha:{uuid}} 的风格）：
 * <pre>
 * email:verify:{scene}:{email}   验证码本体，TTL = code-expire-minutes
 * email:send:cd:{email}          发送冷却，TTL = send-cooldown-seconds
 * email:send:quota:{email}       该邮箱当日发送次数，TTL 24h
 * email:send:ip:{ip}             该 IP 当日发送次数，TTL 24h
 * email:verify:fail:{email}      连续校验失败次数，TTL 10min
 * </pre>
 */
@Slf4j
@Service
public class EmailCodeService {

    public static final String SCENE_REGISTER = "register";
    public static final String SCENE_RESET_PWD = "resetpwd";
    public static final String SCENE_BIND = "bind";
    public static final String SCENE_UPGRADE = "upgrade";

    private static final Set<String> SUPPORTED_SCENES =
            Set.of(SCENE_REGISTER, SCENE_RESET_PWD, SCENE_BIND, SCENE_UPGRADE);

    private static final String KEY_VERIFY = "email:verify:";
    private static final String KEY_SEND_CD = "email:send:cd:";
    private static final String KEY_SEND_QUOTA_EMAIL = "email:send:quota:";
    private static final String KEY_SEND_QUOTA_IP = "email:send:ip:";
    private static final String KEY_VERIFY_FAIL = "email:verify:fail:";

    private static final Duration QUOTA_WINDOW = Duration.ofHours(24);
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10);

    /**
     * 验证码用 SecureRandom，不用 ThreadLocalRandom（后者可预测）
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private EmailProperties emailProperties;

    @Resource
    private EmailService emailService;

    /**
     * 邮箱归一化 + 格式/白名单校验。注册与发信都走这里，保证两边口径一致。
     *
     * @return 归一化后的邮箱
     */
    public String validateAndNormalize(String rawEmail) {
        String normalized = EmailUtils.normalize(rawEmail);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        if (emailProperties.isEnforceAllowedDomains()
                && !EmailUtils.isAllowedDomain(normalized, emailProperties.getAllowedDomains())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "暂不支持该邮箱域名，请使用常用公共邮箱");
        }
        return normalized;
    }

    /**
     * 发送邮箱验证码。
     * 顺序刻意设计为「先发信、成功后再落冷却与配额」：SMTP 抖动时不会把用户
     * 锁在 60 秒冷却里，可以立刻重试。
     *
     * @param scene 业务场景，见本类常量
     * @param email 已归一化的邮箱
     * @param ip    请求方 IP
     */
    public void sendCode(String scene, String email, String ip) {
        if (!SUPPORTED_SCENES.contains(scene)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的验证码场景");
        }
        // 1. 冷却
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(KEY_SEND_CD + email))) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST,
                    "验证码已发送，请 " + emailProperties.getSendCooldownSeconds() + " 秒后再试");
        }
        // 2. 每日配额
        checkDailyQuota(KEY_SEND_QUOTA_EMAIL + email, emailProperties.getDailyLimitPerEmail(), "该邮箱今日发送次数已达上限");
        checkDailyQuota(KEY_SEND_QUOTA_IP + (StrUtil.isBlank(ip) ? "unknown" : ip),
                emailProperties.getDailyLimitPerIp(), "当前网络今日发送次数已达上限");

        // 3. 生成并落库
        String code = randomCode();
        stringRedisTemplate.opsForValue().set(
                verifyKey(scene, email), code, Duration.ofMinutes(emailProperties.getCodeExpireMinutes()));

        // 4. 同步发送
        try {
            emailService.sendVerifyCode(email, code, scene);
        } catch (Exception e) {
            // 发信失败 → 撤掉刚存的验证码，且不落冷却与配额，允许用户立即重试
            stringRedisTemplate.delete(verifyKey(scene, email));
            throw e;
        }

        // 5. 发送成功才落冷却与配额
        stringRedisTemplate.opsForValue().set(
                KEY_SEND_CD + email, "1", Duration.ofSeconds(emailProperties.getSendCooldownSeconds()));
        increaseDailyQuota(KEY_SEND_QUOTA_EMAIL + email);
        increaseDailyQuota(KEY_SEND_QUOTA_IP + (StrUtil.isBlank(ip) ? "unknown" : ip));
    }

    /**
     * 校验邮箱验证码。验证码<b>一次性</b>，校验通过立即作废。
     */
    public void verifyCode(String scene, String email, String inputCode) {
        if (StrUtil.isBlank(inputCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请输入邮箱验证码");
        }
        String failKey = KEY_VERIFY_FAIL + email;
        String codeKey = verifyKey(scene, email);

        // 失败次数超限 → 直接作废当前验证码，不给继续试的机会
        int failCount = parseCount(stringRedisTemplate.opsForValue().get(failKey));
        if (failCount >= emailProperties.getMaxVerifyFail()) {
            stringRedisTemplate.delete(codeKey);
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误次数过多，请重新获取");
        }

        String cachedCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (cachedCode == null || !cachedCode.equals(inputCode.trim())) {
            int current = increase(failKey, FAIL_WINDOW);
            if (current >= emailProperties.getMaxVerifyFail()) {
                stringRedisTemplate.delete(codeKey);
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误次数过多，请重新获取");
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误或已过期");
        }

        // 通过：一次性，立即作废（与登录验证码保持一致）
        stringRedisTemplate.delete(codeKey);
        stringRedisTemplate.delete(failKey);
    }

    /**
     * 主动作废某个场景下的验证码（如注册流程后续校验失败时）
     */
    public void invalidate(String scene, String email) {
        stringRedisTemplate.delete(verifyKey(scene, email));
    }

    private String verifyKey(String scene, String email) {
        return KEY_VERIFY + scene + ":" + email;
    }

    private void checkDailyQuota(String key, int limit, String message) {
        if (parseCount(stringRedisTemplate.opsForValue().get(key)) >= limit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, message);
        }
    }

    private void increaseDailyQuota(String key) {
        increase(key, QUOTA_WINDOW);
    }

    /**
     * 计数 +1，并在首次计数时设置 TTL（后续递增不刷新，保证是"自然日窗口"而非滑动窗口）
     */
    private int increase(String key, Duration ttl) {
        Long count = stringRedisTemplate.opsForValue().increment(key);
        long value = count == null ? 1L : count;
        if (value == 1L) {
            stringRedisTemplate.expire(key, ttl);
        }
        return (int) value;
    }

    private int parseCount(String value) {
        if (StrUtil.isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String randomCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
