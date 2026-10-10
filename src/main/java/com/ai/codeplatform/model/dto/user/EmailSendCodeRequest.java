package com.ai.codeplatform.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 发送邮箱验证码请求。
 * <p>
 * 必须携带图形验证码：否则这个接口本身就成了新的靶子，
 * 别人可以拿它当免费发信炮台，对任意邮箱无限发信。
 */
@Data
public class EmailSendCodeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标邮箱
     */
    private String email;

    /**
     * 业务场景：register / resetpwd / bind / upgrade
     */
    private String scene;

    /**
     * 图形验证码的 key（由 /user/getCaptcha 返回）
     */
    private String captchaKey;

    /**
     * 图形验证码
     */
    private String captchaCode;
}
