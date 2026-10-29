package com.ai.codeplatform.service;

import com.ai.codeplatform.model.dto.announcement.AnnouncementSaveRequest;
import com.ai.codeplatform.model.entity.Announcement;
import com.ai.codeplatform.model.vo.AnnouncementVO;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 公告服务。
 */
public interface AnnouncementService extends IService<Announcement> {

    /**
     * 用户端可见的公告：已发布 + 已到发布时间 + 未过期。
     * <p>
     * 三个条件缺一不可 —— 只看 status 会把定时发布和已过期的一起放出来。
     *
     * @return 按置顶、发布时间倒序，最多 20 条
     */
    List<AnnouncementVO> listVisible();

    /**
     * 管理端分页查询（含草稿、已下线）
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param keyword  标题 / 正文关键词，可空
     * @param status   状态筛选，可空
     */
    Page<Announcement> pageForAdmin(int pageNum, int pageSize, String keyword, Integer status);

    /**
     * 新增或编辑
     *
     * @param request  请求体
     * @param userId   操作人 id
     * @return 公告 id
     */
    Long saveAnnouncement(AnnouncementSaveRequest request, Long userId);

    /**
     * 发布：状态置为已发布，没设发布时间就补上当前时间
     */
    boolean publish(Long id);

    /**
     * 下线：状态置为已下线，用户端立刻不可见
     */
    boolean offline(Long id);

    /**
     * 软删
     */
    boolean removeAnnouncement(Long id);
}
