package com.ai.codeplatform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 邮箱验证码相关配置（app.mail）
 * <p>
 * 限流阈值全部给了默认值，yml 里可以只覆盖需要的项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.mail")
public class EmailProperties {

    /**
     * 发件地址。留空则回退使用 spring.mail.username
     */
    private String from;

    /**
     * 发件人展示名
     */
    private String fromName = "iCodeAI";

    /**
     * 验证码有效期（分钟）
     */
    private int codeExpireMinutes = 5;

    /**
     * 同一邮箱的发送冷却时间（秒）
     */
    private int sendCooldownSeconds = 60;

    /**
     * 同一邮箱每日发送上限
     */
    private int dailyLimitPerEmail = 10;

    /**
     * 同一 IP 每日发送上限（防止拿服务当发信炮台）
     */
    private int dailyLimitPerIp = 30;

    /**
     * 同一邮箱连续校验失败上限，超过则作废当前验证码
     * （6 位数字只有 10^6 空间，不限制会被脚本撞开）
     */
    private int maxVerifyFail = 5;

    /**
     * 是否启用邮箱域名白名单。
     * 白名单能有效拦掉一次性邮箱，但也会拦掉企业自有域名，
     * 所以留了这个开关：本地联调时置 false 即可放行任意域名。
     */
    private boolean enforceAllowedDomains = true;

    /**
     * 在内置白名单之外额外放行的域名。
     * 例如公司邮箱、你的测试邮箱所在域名。
     */
    private List<String> allowedDomains = new ArrayList<>();
}
