package com.ai.codeplatform.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告 实体类。
 * <p>
 * 可见性由三个字段共同决定：status = 1、publishTime 已到、expireTime 未过。
 * 不要只看 status —— 定时发布和过期下线的公告 status 仍是 1。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("announcement")
public class Announcement implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 公告标题
     */
    private String title;

    /**
     * 公告正文（Markdown）
     */
    private String content;

    /**
     * 级别：INFO / WARNING / SUCCESS，前端据此决定弹窗配色
     */
    private String level;

    /**
     * 状态：0 草稿、1 已发布、2 已下线
     */
    private Integer status;

    /**
     * 是否置顶：0 否、1 是
     */
    @Column("topFlag")
    private Integer topFlag;

    /**
     * 发布时间，为空表示立即生效
     */
    @Column("publishTime")
    private LocalDateTime publishTime;

    /**
     * 过期时间，为空表示长期有效
     */
    @Column("expireTime")
    private LocalDateTime expireTime;

    /**
     * 发布人 id
     */
    @Column("createUserId")
    private Long createUserId;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除：0 否、1 是（软删，列表查询都要带上 isDelete = 0）
     */
    @Column("isDelete")
    private Integer isDelete;
}
