package com.ai.codeplatform.aop;

import cn.hutool.core.util.ReflectUtil;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.annotation.LogRecord;
import com.ai.codeplatform.common.BaseResponse;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.model.entity.App;
import com.ai.codeplatform.model.entity.SysOperationLog;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.service.SysOperationLogService;
import com.fasterxml.jackson.databind.ser.Serializers;
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

import java.lang.reflect.Field;
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
                .map(arg -> {
                    // 2. 对简单类型直接转为字符串，避免序列化为 {}
                    if (arg == null) {
                        return "null";
                    }
                    if (arg instanceof String || arg instanceof Number ||
                            arg instanceof Boolean || arg instanceof Character) {
                        return arg.toString();
                    }
                    // 3. 复杂对象使用 JSON 序列化
                    return JSONUtil.toJsonStr(arg);
                })
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
        //获取appId
        Long appId = extractAppId(joinPoint);
        long startTime = System.currentTimeMillis();
        SysOperationLog logInfo = SysOperationLog.builder()
                .requestParams(JSONUtil.toJsonStr(argsList))
                .methodName(methodName)
                .appId(appId)
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
            if (result instanceof BaseResponse<?>){
                BaseResponse baseResponse = (BaseResponse) result;
                Object data = baseResponse.getData();
                // 安全提取 appId，只有当 data 是 Long 类型时才设置
                if (data instanceof Long) {
                    logInfo.setAppId((Long) data);
                } else if (data instanceof Number) {
                    // 兼容其他数字类型
                    logInfo.setAppId(((Number) data).longValue());
                }
                // 如果 data 不是数字类型（如 Boolean、String、List 等），则不设置 appId
            }
            if (result instanceof Flux<?>){
                Flux<?> flux = (Flux<?>) result;
                // 在流完成时记录日志
                return flux
                        .doOnComplete(() -> {
                            recordLog(logInfo,startTime);
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
            recordLog(logInfo, startTime);
            saveLog(logInfo);
        } catch (Throwable e) {
            // 先记录失败
            logInfo.setStatus("FAILED");
            logInfo.setEndTime(LocalDateTime.now());
            logInfo.setDurationMs((int) (System.currentTimeMillis() - startTime));

            // 根据异常类型分类记录
            if (e instanceof BusinessException) {
                BusinessException be = (BusinessException) e;
                logInfo.setOperation("业务异常: " + (be.getMessage() != null ? be.getMessage() : "未知业务错误"));
                log.warn("业务异常 - 错误码: {}, 消息: {}", be.getCode(), be.getMessage());
            } else if (e instanceof RuntimeException) {
                logInfo.setOperation("运行时异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("运行时异常 - 用户ID: {}, URI: {}", logInfo.getUserId(), logInfo.getRequestUri(), e);
            } else {
                logInfo.setOperation("系统异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("系统异常 - 用户ID: {}, URI: {}", logInfo.getUserId(), logInfo.getRequestUri(), e);
            }
            // 统一保存日志
            saveLog(logInfo);

            // 重新抛出原始异常（让 GlobalExceptionHandler 处理返回给前端）
            if (e instanceof BusinessException){
                throw (BusinessException) e;
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);      // 非运行时异常兜底
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
     * 补充日志记录成功字段
     * @param logInfo
     * @param startTime
     */
    private void recordLog(SysOperationLog logInfo, long startTime) {
        logInfo.setEndTime(LocalDateTime.now());
        logInfo.setDurationMs((int) (System.currentTimeMillis() - startTime));
        logInfo.setStatus("SUCCESS");
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

    /**
     * 从方法参数中提取 appId
     *
     * @param joinPoint 切点
     * @return appId，如果无法提取则返回 null
     */
    private Long extractAppId(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            String methodName = signature.getMethod().getName();
            Object[] args = joinPoint.getArgs();

            // 方法1: chatToGenCode - 直接从 @RequestParam 参数获取
            if ("chatToGenCode".equals(methodName) && args.length >= 1) {
                if (args[0] instanceof Long) {
                    return (Long) args[0];
                }
            }

            // 方法2 & 3: deleteApp / deleteAppByAdmin - 从 DeleteRequest 对象获取
            if ("deleteApp".equals(methodName) || "deleteAppByAdmin".equals(methodName)) {
                if (args.length >= 1 && args[0] != null) {
                    // 通过反射获取 id 字段
                    Field idField = ReflectUtil.getField(args[0].getClass(), "id");
                    if (idField != null) {
                        ReflectUtil.setAccessible(idField);
                        Object idValue = ReflectUtil.getFieldValue(args[0], idField);
                        if (idValue instanceof Long) {
                            return (Long) idValue;
                        } else if (idValue instanceof Number) {
                            return ((Number) idValue).longValue();
                        }
                    }
                }
            }
        }catch (Exception e) {
            log.warn("提取 appId 失败: {}", e.getMessage());
        }
        return null;
    }
}
