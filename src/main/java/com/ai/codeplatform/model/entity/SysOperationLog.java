package com.ai.codeplatform.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

import java.io.Serial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
public class SysOperationLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 应用ID
     */
    private Long appId;

    /**
     * 用户姓名(冗余字段，方便查询)
     */
    private String username;

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

    /**
     * 开始时间
     */
    @Column("startTime")
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    @Column("endTime")
    private LocalDateTime endTime;

    /**
     * 耗时（毫秒）
     */
    @Column("durationMs")
    private Integer durationMs;

    /**
     * 方法操作说明
     */
    private String operation;

    /**
     * 状态：SUCCESS/FAILED
     */
    private String status;

    /**
     * 本次调用使用的 AI 模型名（仅 AI 生成类接口有值）
     */
    @Column("modelName")
    private String modelName;

    /**
     * 提示词侧 token 数
     */
    @Column("inputTokens")
    private Integer inputTokens;

    /**
     * 生成侧 token 数
     */
    @Column("outputTokens")
    private Integer outputTokens;

    /**
     * 合计 token 数
     */
    @Column("totalTokens")
    private Integer totalTokens;

    /**
     * 模型调用轮次：1 表示单轮、没有工具调用；数字大通常意味着反复构建/修复
     */
    @Column("modelRounds")
    private Integer modelRounds;

    /**
     * 用量是否为「中断时的部分值」：true 表示没有拿到最后一轮 usage，数字偏小
     */
    @Column("usagePartial")
    private Boolean usagePartial;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

}
