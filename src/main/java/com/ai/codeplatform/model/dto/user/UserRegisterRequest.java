package com.ai.codeplatform.model.dto.user;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 账号（不允许包含 @，避免与邮箱登录撞号）
     */
    private String userAccount;

    /**
     * 邮箱（必填，注册时需通过邮箱验证码验证）
     */
    private String email;

    /**
     * 邮箱验证码
     */
    private String emailCode;

    /**
     * 密码
     */
    private String userPassword;

    /**
     * 确认密码
     */
    private String checkPassword;
}
