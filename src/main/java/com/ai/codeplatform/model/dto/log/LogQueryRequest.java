package com.ai.codeplatform.model.dto.log;

import com.ai.codeplatform.common.PageRequest;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统操作日志表 实体类。
 *
 * @author Administrator
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("sys_operation_log")
public class LogQueryRequest extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户姓名(冗余字段，方便查询)
     */
    private String username;

    /**
     * 状态
     */
    private String status;

    /**
     * 操作说明
     */
    private String operation;

    /**
     * 请求IP地址
     */
    private String ipAddress;

    /**
     * 请求路径(URI)
     */
    private String requestUri;

    /**
     * 请求方式(GET/POST/PUT/DELETE)
     */
    private String requestMethod;

    /**
     * 方法名
     */
    private String methodName;

    /**
     * 请求参数(JSON字符串)
     */
    private String requestParams;

    /**
     * 操作时间
     */
    private LocalDateTime createTime;

}
