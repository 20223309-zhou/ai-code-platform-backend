package com.ai.codeplatform.model.dto.announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告新增 / 编辑请求。
 * <p>
 * 新增与编辑共用一个接口：id 为空即新增。
 */
@Data
public class AnnouncementSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 为空表示新增
     */
    private Long id;

    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题最多 128 个字符")
    private String title;

    @NotBlank(message = "正文不能为空")
    private String content;

    /**
     * INFO / WARNING / SUCCESS，非法值会被规整成 INFO
     */
    private String level;

    /**
     * 是否置顶
     */
    private Integer topFlag;

    /**
     * 定时发布时间，为空表示立即
     */
    private LocalDateTime publishTime;

    /**
     * 过期时间，为空表示长期有效
     */
    private LocalDateTime expireTime;

    /**
     * true：保存后直接发布；false：存为草稿
     */
    private Boolean publishNow;
}
