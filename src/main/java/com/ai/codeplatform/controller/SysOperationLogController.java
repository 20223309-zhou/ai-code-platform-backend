package com.ai.codeplatform.controller;

import com.ai.codeplatform.annotation.AuthCheck;
import com.ai.codeplatform.common.BaseResponse;
import com.ai.codeplatform.common.ResultUtils;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.model.dto.log.LogDeleteRequest;
import com.ai.codeplatform.model.dto.log.LogQueryRequest;
import com.ai.codeplatform.model.entity.SysOperationLog;
import com.ai.codeplatform.service.SysOperationLogService;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 系统操作日志表 控制层。
 *
 * @author Administrator
 */
@RestController
@RequestMapping("/sysOperationLog")
public class SysOperationLogController {

    @Autowired
    private SysOperationLogService sysOperationLogService;

    /**
     * 获取系统操作日志表列表。
     * @return 系统操作日志表列表。
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @PostMapping("/list")
    public BaseResponse<Page<SysOperationLog>> listByPages(@RequestBody LogQueryRequest logQueryRequest) {
        if (logQueryRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        int pageNum = logQueryRequest.getPageNum();
        int pageSize = logQueryRequest.getPageSize();
        if (pageSize > 100){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "每页条数不能超过100");
        }
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(SysOperationLog::getUserId, logQueryRequest.getUserId())
                .eq(SysOperationLog::getUsername, logQueryRequest.getUsername())
                .eq(SysOperationLog::getIpAddress, logQueryRequest.getIpAddress())
                .eq(SysOperationLog::getStatus, logQueryRequest.getStatus())
                .like(SysOperationLog::getOperation, logQueryRequest.getOperation())
                .eq(SysOperationLog::getRequestUri, logQueryRequest.getRequestUri())
                .eq(SysOperationLog::getRequestMethod, logQueryRequest.getRequestMethod())
                .eq(SysOperationLog::getMethodName, logQueryRequest.getMethodName())
                .eq(SysOperationLog::getRequestParams, logQueryRequest.getRequestParams())
                .eq(SysOperationLog::getCreateTime, logQueryRequest.getCreateTime())
                .eq(SysOperationLog::getId, logQueryRequest.getId())
                .orderBy(SysOperationLog::getCreateTime, false);
        Page<SysOperationLog> page = sysOperationLogService.page(Page.of(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(page);
    }
    /**
     * 删除系统操作日志表。
     * @param LogDeleteRequest
     * @return
     */
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @DeleteMapping("/delete")
    public BaseResponse<Boolean> delete(@RequestBody LogDeleteRequest LogDeleteRequest) {
        if (LogDeleteRequest == null || LogDeleteRequest.getIds().isEmpty()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        return ResultUtils.success(sysOperationLogService.removeByIds(LogDeleteRequest.getIds()));
    }
}
