package com.ai.codeplatform.service.email;

import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.config.EmailProperties;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 邮件发送。只负责"把信发出去"，不含验证码的存取与配额控制。
 * 这里刻意做成<b>同步发送</b>：SMTP 已配置 5 秒超时，虽然接口会慢 1~3 秒，
 * 但发送失败能如实告诉用户"邮件发送失败，请稍后重试"。
 * 若改成异步，接口总是返回成功，用户收不到信又会被冷却时间挡住，体验更差。
 */
@Slf4j
@Service
public class EmailService {

    private static final String SCENE_REGISTER = "register";
    private static final String SCENE_RESET_PWD = "resetpwd";
    private static final String SCENE_BIND = "bind";
    private static final String SCENE_UPGRADE = "upgrade";

    @Resource
    private JavaMailSender mailSender;

    @Resource
    private EmailProperties emailProperties;

    /**
     * spring.mail.username，作为未单独配置 app.mail.from 时的发件地址兜底
     */
    @Value("${spring.mail.username:}")
    private String mailUsername;

    /**
     * 发送邮箱验证码邮件
     *
     * @param to    收件地址（应为归一化后的邮箱）
     * @param code  验证码
     * @param scene 业务场景，决定邮件文案
     */
    public void sendVerifyCode(String to, String code, String scene) {
        String from = StrUtil.isNotBlank(emailProperties.getFrom())
                ? emailProperties.getFrom()
                : mailUsername;
        if (StrUtil.isBlank(from)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "邮件服务未配置发件地址");
        }
        int expireMinutes = emailProperties.getCodeExpireMinutes();
        String purpose = purposeOf(scene);
        try {
            var message = mailSender.createMimeMessage();
            // true = multipart，同时发纯文本与 HTML 两份，避免部分客户端正文显示异常
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, emailProperties.getFromName());
            helper.setTo(to);
            helper.setSubject("【" + emailProperties.getFromName() + "】" + purpose + "验证码：" + code);
            helper.setText(
                    plainText(purpose, code, expireMinutes),
                    htmlText(purpose, code, expireMinutes)
            );
            mailSender.send(message);
            log.info("邮箱验证码已发送, scene: {}, to: {}", scene, to);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 不要把 SMTP 异常细节透给前端（可能含服务器地址、账号信息）
            log.error("邮箱验证码发送失败, scene: {}, to: {}", scene, to, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "邮件发送失败，请稍后重试");
        }
    }

    private String purposeOf(String scene) {
        if (scene == null) {
            return "身份";
        }
        return switch (scene) {
            case SCENE_REGISTER -> "注册";
            case SCENE_RESET_PWD -> "找回密码";
            case SCENE_BIND -> "绑定邮箱";
            case SCENE_UPGRADE -> "额度升级";
            default -> "身份";
        };
    }

    private String plainText(String purpose, String code, int expireMinutes) {
        return "您的" + purpose + "验证码是：" + code + "\n\n"
                + "有效期 " + expireMinutes + " 分钟，请勿转发给他人。\n"
                + "如果这不是您本人的操作，请忽略本邮件。";
    }

    private String htmlText(String purpose, String code, int expireMinutes) {
        return "<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;"
                + "max-width:480px;margin:0 auto;padding:24px;color:#2c2c2a;\">"
                + "<h2 style=\"font-size:18px;font-weight:600;margin:0 0 16px;\">" + purpose + "验证码</h2>"
                + "<p style=\"font-size:14px;line-height:1.6;margin:0 0 16px;\">您正在进行"
                + purpose + "操作，验证码如下：</p>"
                + "<div style=\"font-size:28px;font-weight:700;letter-spacing:6px;padding:16px 0;"
                + "text-align:center;background:#f1efe8;border-radius:8px;\">" + code + "</div>"
                + "<p style=\"font-size:13px;line-height:1.6;margin:16px 0 0;color:#5f5e5a;\">"
                + "有效期 " + expireMinutes + " 分钟，请勿转发给他人。</p>"
                + "<p style=\"font-size:13px;line-height:1.6;margin:8px 0 0;color:#5f5e5a;\">"
                + "如果这不是您本人的操作，请忽略本邮件。</p>"
                + "</div>";
    }
}
