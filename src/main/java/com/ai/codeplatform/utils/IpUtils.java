package com.ai.codeplatform.utils;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 提取。
 * <p>
 * 从 {@code RateLimitAspect#getClientIP} 提取出来共用，避免"取真实 IP"这段
 * 涉及代理头的安全敏感逻辑在多个地方各写一份、逐渐走样。
 */
public final class IpUtils {

    private IpUtils() {
    }

    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (isUnknown(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (isUnknown(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时 X-Forwarded-For 是逗号分隔的链路，取第一个（最初的客户端）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return StrUtil.isBlank(ip) ? "unknown" : ip;
    }

    private static boolean isUnknown(String ip) {
        return ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip);
    }
}
