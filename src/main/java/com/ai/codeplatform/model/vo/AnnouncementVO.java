package com.ai.codeplatform.model.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户端可见的公告。
 * <p>
 * 故意不给 createUserId / status 这些管理字段 —— 用户端只需要渲染弹窗，
 * 少传字段也少一个泄露面。
 */
@Data
public class AnnouncementVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /**
     * Markdown 正文，前端用 MarkdownRenderer 渲染
     */
    private String content;

    /**
     * INFO / WARNING / SUCCESS
     */
    private String level;

    private Integer topFlag;

    private LocalDateTime publishTime;
}
