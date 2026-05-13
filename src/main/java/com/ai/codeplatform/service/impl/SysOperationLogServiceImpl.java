package com.ai.codeplatform.service.impl;

import com.ai.codeplatform.model.entity.SysOperationLog;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ai.codeplatform.mapper.SysOperationLogMapper;
import com.ai.codeplatform.service.SysOperationLogService;
import org.springframework.stereotype.Service;

/**
 * 系统操作日志表 服务层实现。
 *
 * @author Administrator
 */
@Service
public class SysOperationLogServiceImpl extends ServiceImpl<SysOperationLogMapper, SysOperationLog>  implements SysOperationLogService{

}
