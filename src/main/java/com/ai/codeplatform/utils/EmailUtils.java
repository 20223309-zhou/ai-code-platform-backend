package com.ai.codeplatform.utils;

import cn.hutool.core.util.StrUtil;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 邮箱归一化与域名白名单校验。
 */
public final class EmailUtils {

    private EmailUtils() {
    }

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /**
     * 允许注册的公共邮箱域名白名单。
     * <p>
     * 这里刻意用<b>白名单而不是黑名单</b>：一次性邮箱域名有几千个且天天新增，黑名单永远追不上。
     * 白名单的代价是会拦掉企业自有域名，需要时按需追加即可。
     */
    private static final Set<String> ALLOWED_DOMAINS = Set.of(
            // 腾讯
            "qq.com", "foxmail.com",
            // 网易
            "163.com", "126.com", "yeah.net",
            // 谷歌 / 微软
            "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com",
            // 国内其他公共邮箱
            "sina.com", "sina.cn", "sohu.com", "aliyun.com",
            // 运营商邮箱
            "139.com", "189.cn", "wo.cn"
    );

    /**
     * 基础格式校验（不做归一化）
     */
    public static boolean isEmailFormat(String email) {
        return StrUtil.isNotBlank(email) && email.length() <= 256 && EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    /**
     * 归一化：去空格 → 转小写 → Gmail 去掉 +后缀 与 点号。
     * <p>
     * 必须归一化后再入库，否则 a@qq.com / A@qq.com、a+x@gmail.com / a.x@gmail.com
     * 会被当成不同账号，唯一索引形同虚设。
     *
     * @return 归一化后的邮箱；入参非法时返回 null
     */
    public static String normalize(String email) {
        if (!isEmailFormat(email)) {
            return null;
        }
        String value = email.trim().toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        if ("gmail.com".equals(domain) || "googlemail.com".equals(domain)) {
            local = local.split("\\+", 2)[0].replace(".", "");
            domain = "gmail.com";
        }
        if (local.isEmpty()) {
            return null;
        }
        return local + "@" + domain;
    }

    /**
     * 域名是否在白名单内。传入的应是 {@link #normalize(String)} 之后的值
     */
    public static boolean isAllowedDomain(String normalizedEmail) {
        return isAllowedDomain(normalizedEmail, null);
    }

    /**
     * 域名是否在白名单内，可额外放行若干域名（来自 app.mail.allowed-domains）
     */
    public static boolean isAllowedDomain(String normalizedEmail, Collection<String> extraAllowed) {
        if (StrUtil.isBlank(normalizedEmail)) {
            return false;
        }
        int at = normalizedEmail.indexOf('@');
        if (at < 0) {
            return false;
        }
        String domain = normalizedEmail.substring(at + 1);
        if (ALLOWED_DOMAINS.contains(domain)) {
            return true;
        }
        if (extraAllowed == null || extraAllowed.isEmpty()) {
            return false;
        }
        for (String extra : extraAllowed) {
            if (StrUtil.isBlank(extra)) {
                continue;
            }
            if (domain.equals(extra.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
