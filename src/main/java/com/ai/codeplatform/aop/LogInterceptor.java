package com.ai.codeplatform.aop;

import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.annotation.LogRecord;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.model.entity.SysOperationLog;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.service.SysOperationLogService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;

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
    @Around("@annotation(logRecord)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, LogRecord logRecord) {
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
        long startTime = System.currentTimeMillis();
        SysOperationLog logInfo = SysOperationLog.builder()
                .userId(userId)
                .username(userName)
                .ipAddress(ipAddress)
                .requestUri(requestURI)
                .requestMethod(requestMethod)
                .startTime(LocalDateTime.now())
                .operation(logRecord.description())
                .createTime(LocalDateTime.now())
                .status("RUNNING")
                .build();
        Object result = null;
        try {
            // 执行方法
            result =joinPoint.proceed();
            if (result instanceof Flux<?>){
                Flux<?> flux = (Flux<?>) result;
                // 在流完成时记录日志
                return flux
                        .doOnComplete(() -> {
                            recordLog(joinPoint,logInfo,startTime);
                            saveLog(logInfo);
                        })
                        .doOnError(error -> {
                            long duration = System.currentTimeMillis() - startTime;
                            logInfo.setEndTime(LocalDateTime.now());
                            logInfo.setDurationMs((int) duration);
                            logInfo.setStatus("FAILED");
                            saveLog(logInfo);
                            log.error("流式接口调用异常");
                        });
            }
            recordLog(joinPoint, logInfo, startTime);
            saveLog(logInfo);
        } catch (Throwable e) {
            // 记录失败状态
            logInfo.setStatus("FAILED");
            logInfo.setEndTime(LocalDateTime.now());
            logInfo.setDurationMs((int) (System.currentTimeMillis() - startTime));
            log.error("接口调用异常", e);
            saveLog(logInfo);
        }
        return result;
    }

    /**
     * 保存日志
     *
     * @param logInfo 日志信息
     */
    private void saveLog(SysOperationLog logInfo) {
        boolean isSuccess = sysOperationLogService.save(logInfo);
        if (!isSuccess){
            log.error("接口调用日志写入数据库失败");
        }
    }

    /**
     * 补充日志记录字段
     * @param joinPoint
     * @param logInfo
     * @param startTime
     */
    private void recordLog(ProceedingJoinPoint joinPoint, SysOperationLog logInfo, long startTime) {
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

        logInfo.setEndTime(LocalDateTime.now());
        logInfo.setDurationMs((int) (System.currentTimeMillis() - startTime));
        logInfo.setStatus("SUCCESS");
        logInfo.setMethodName(methodName);
        logInfo.setRequestParams(JSONUtil.toJsonStr(argsList));
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
