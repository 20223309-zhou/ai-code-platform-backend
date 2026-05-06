package com.ai.codeplatform.aop;

import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.model.entity.SysOperationLog;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.service.SysOperationLogService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
@Aspect
public class LogInterceptor {
    @Resource
    private SysOperationLogService sysOperationLogService;
    @AfterReturning("@annotation(com.ai.codeplatform.annotation.LogRecord)")
    public void doInterceptor(JoinPoint joinPoint) {
        try {
            Signature signature = joinPoint.getSignature();
            MethodSignature mSignature = (MethodSignature) signature;
            // 获取方法名
            String methodName = mSignature.getMethod().getName();
            // 获取方法参数
            Object[] args = joinPoint.getArgs();
            List<String> argsList = Arrays.stream(args)
                    .filter(arg -> {
                        // 1. 过滤掉 Request、Response 等原生对象，不记录它们
                        if (arg instanceof HttpServletRequest || arg instanceof HttpServletResponse) {
                            return false;
                        }
                        return true;
                    })
                    .map(JSONUtil::toJsonStr)
                    .collect(Collectors.toList());

            RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
            ServletRequestAttributes servletRequestAttributes = (ServletRequestAttributes) requestAttributes;
            HttpServletRequest request = servletRequestAttributes.getRequest();
            // 获取当前用户
            Object user = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
            User loginUser = (User) user;
            Long userId = loginUser.getId();
            String userName = loginUser.getUserName();
            // 获取远程地址
            String ipAddress = getIpAddress(request);
            // 获取请求路径
            String requestURI = request.getRequestURI();
            // 获取请求方式
            String requestMethod = request.getMethod();
            SysOperationLog logInfo = SysOperationLog.builder()
                    .userId(userId)
                    .username(userName)
                    .ipAddress(ipAddress)
                    .requestUri(requestURI)
                    .requestMethod(requestMethod)
                    .methodName(methodName)
                    .requestParams(JSONUtil.toJsonStr(argsList))
                    .createTime(LocalDateTime.now())
                    .build();
            boolean result = sysOperationLogService.save(logInfo);
            if (!result){
                log.error("接口调用日志记录失败");
            }

            log.info("接口调用日志记录：用户Id：{}，请求路径：{}，请求IP：{}，请求方式：{}，请求参数：{}，请求方法：{}",
                    userId, requestURI, ipAddress ,requestMethod, argsList, methodName);
        } catch (Exception e) {
            log.error("接口调用日志记录失败", e);
        }
    }
    /**
     * 获取IP地址
     */
    public String getIpAddress(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
            // 处理本地 IPv6 回环地址
            if ("0:0:0:0:0:0:0:1".equals(ip)) {
                ip = "127.0.0.1";
            }
        }
        // 如果是通过多级反向代理，第一个IP为客户端真实IP,多个IP按照','分割
        if (ip != null && ip.length() > 15) {
            if (ip.indexOf(",") > 0) {
                ip = ip.substring(0, ip.indexOf(","));
            }
        }
        return ip;
    }

}
