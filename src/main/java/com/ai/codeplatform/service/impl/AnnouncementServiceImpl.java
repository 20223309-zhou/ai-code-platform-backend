package com.ai.codeplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.mapper.AnnouncementMapper;
import com.ai.codeplatform.model.dto.announcement.AnnouncementSaveRequest;
import com.ai.codeplatform.model.entity.Announcement;
import com.ai.codeplatform.model.vo.AnnouncementVO;
import com.ai.codeplatform.service.AnnouncementService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 公告 服务层实现。
 */
@Slf4j
@Service
public class AnnouncementServiceImpl extends ServiceImpl<AnnouncementMapper, Announcement>
        implements AnnouncementService {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /** 已发布 */
    private static final int STATUS_PUBLISHED = 1;
    /** 已下线 */
    private static final int STATUS_OFFLINE = 2;

    /** 只认这三个值，其他一律按 INFO 处理，避免前端拿到未知配色 */
    private static final Set<String> LEVELS = Set.of("INFO", "WARNING", "SUCCESS");

    /** 用户端一次最多给 20 条：弹窗只看最新一条，其余当历史 */
    private static final int MAX_VISIBLE = 20;

    private static final String CACHE_KEY = "announcement:visible";

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    @Override
    public List<AnnouncementVO> listVisible() {
        // 先查询缓存
        String cached = redisTemplate.opsForValue().get(CACHE_KEY);
        if (StrUtil.isNotBlank(cached)) {
            return JSONUtil.toList(cached, AnnouncementVO.class);
        }

        LocalDateTime now = LocalDateTime.now();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .where("status = ?", STATUS_PUBLISHED)
                .and("isDelete = 0")
                // 定时发布：还没到点的不给
                .and("(publishTime IS NULL OR publishTime <= ?)", now)
                // 过期下线：过了点的不给
                .and("(expireTime IS NULL OR expireTime > ?)", now)
                .orderBy("topFlag DESC, publishTime DESC, createTime DESC")
                .limit(MAX_VISIBLE);

        List<AnnouncementVO> list = this.list(queryWrapper).stream()
                .map(item -> BeanUtil.copyProperties(item, AnnouncementVO.class))
                .toList();
        // 空列表也写进去，"[]" 非空串，下次能直接命中
        redisTemplate.opsForValue().set(CACHE_KEY, JSONUtil.toJsonStr(list), CACHE_TTL);
        return list;
    }

    @Override
    public Page<Announcement> pageForAdmin(int pageNum, int pageSize, String keyword, Integer status) {
        QueryWrapper queryWrapper = QueryWrapper.create().where("isDelete = 0");
        if (StrUtil.isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            queryWrapper.and("(title LIKE ? OR content LIKE ?)", like, like);
        }
        if (status != null) {
            queryWrapper.and("status = ?", status);
        }
        queryWrapper.orderBy("topFlag DESC, createTime DESC");
        return this.page(Page.of(pageNum, pageSize), queryWrapper);
    }

    @Override
    public Long saveAnnouncement(AnnouncementSaveRequest request, Long userId) {
        String level = LEVELS.contains(request.getLevel()) ? request.getLevel() : "INFO";
        boolean publishNow = Boolean.TRUE.equals(request.getPublishNow());
        LocalDateTime now = LocalDateTime.now();

        if (request.getId() == null) {
            Announcement announcement = Announcement.builder()
                    .title(request.getTitle().trim())
                    .content(request.getContent())
                    .level(level)
                    .status(publishNow ? STATUS_PUBLISHED : 0)
                    .topFlag(request.getTopFlag() == null ? 0 : request.getTopFlag())
                    // 立即发布的没有显式发布时间，补当前时间；定时发布的用用户填的值
                    .publishTime(request.getPublishTime() != null
                            ? request.getPublishTime()
                            : (publishNow ? now : null))
                    .expireTime(request.getExpireTime())
                    .createUserId(userId)
                    .createTime(now)
                    .updateTime(now)
                    .isDelete(0)
                    .build();
            if (!this.save(announcement)) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "公告保存失败");
            }
            return announcement.getId();
        }

        Announcement existing = this.getById(request.getId());
        if (existing == null || existing.getIsDelete() != null && existing.getIsDelete() == 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "公告不存在");
        }

        // 用 set 而非整体覆盖：updateById 会忽略 null 字段，
        // 单纯 copy 一份请求体容易把 publishTime 之类误清空
        Announcement update = new Announcement();
        update.setId(request.getId());
        update.setTitle(request.getTitle().trim());
        update.setContent(request.getContent());
        update.setLevel(level);
        update.setTopFlag(request.getTopFlag() == null ? existing.getTopFlag() : request.getTopFlag());
        update.setExpireTime(request.getExpireTime());
        update.setUpdateTime(now);

        if (request.getPublishTime() != null) {
            update.setPublishTime(request.getPublishTime());
        }
        if (publishNow) {
            update.setStatus(STATUS_PUBLISHED);
            if (existing.getPublishTime() == null && request.getPublishTime() == null) {
                update.setPublishTime(now);
            }
        } else if (existing.getStatus() != null && existing.getStatus() == STATUS_OFFLINE) {
            // 已下线的公告改完内容不会自动重新上线，需要显式点发布
            update.setStatus(STATUS_OFFLINE);
        }

        if (!this.updateById(update)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "公告保存失败");
        }
        redisTemplate.delete(CACHE_KEY);
        return request.getId();
    }

    @Override
    public boolean publish(Long id) {
        Announcement announcement = requireExisting(id);
        Announcement update = new Announcement();
        update.setId(id);
        update.setStatus(STATUS_PUBLISHED);
        // 定时发布的公告点「立即发布」时，把发布时间拉回来
        if (announcement.getPublishTime() == null
                || announcement.getPublishTime().isAfter(LocalDateTime.now())) {
            update.setPublishTime(LocalDateTime.now());
        }
        update.setUpdateTime(LocalDateTime.now());
        redisTemplate.delete(CACHE_KEY);
        return this.updateById(update);
    }

    @Override
    public boolean offline(Long id) {
        requireExisting(id);
        Announcement update = new Announcement();
        update.setId(id);
        update.setStatus(STATUS_OFFLINE);
        update.setUpdateTime(LocalDateTime.now());
        redisTemplate.delete(CACHE_KEY);
        return this.updateById(update);
    }

    @Override
    public boolean removeAnnouncement(Long id) {
        requireExisting(id);
        Announcement update = new Announcement();
        update.setId(id);
        update.setIsDelete(1);
        update.setUpdateTime(LocalDateTime.now());
        redisTemplate.delete(CACHE_KEY);
        return this.updateById(update);
    }

    private Announcement requireExisting(Long id) {
        Announcement announcement = id == null ? null : this.getById(id);
        if (announcement == null || (announcement.getIsDelete() != null && announcement.getIsDelete() == 1)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "公告不存在");
        }
        return announcement;
    }
}
