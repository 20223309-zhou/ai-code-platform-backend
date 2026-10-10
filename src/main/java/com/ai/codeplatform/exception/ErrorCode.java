package com.ai.codeplatform.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    // 独立错误码：验证码错误原先与"账号或密码错误"共用 PARAMS_ERROR，
    // 前端只能靠正则匹配文案区分，后端改一个字就会失效。现在前端按 code 判断。
    CAPTCHA_ERROR(40001, "验证码错误或已过期"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败"),
    TOO_MANY_REQUEST(42900, "请求过于频繁");


    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

}
