package com.ai.codeplatform.controller;

import com.ai.codeplatform.annotation.AuthCheck;
import com.ai.codeplatform.common.BaseResponse;
import com.ai.codeplatform.common.ResultUtils;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.model.dto.announcement.AnnouncementSaveRequest;
import com.ai.codeplatform.model.entity.Announcement;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.model.vo.AnnouncementVO;
import com.ai.codeplatform.service.AnnouncementService;
import com.ai.codeplatform.service.UserService;
import com.mybatisflex.core.paginate.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 公告控制器。
 * 用户端只有一个只读接口；写操作全部挂在 /admin 下并校验管理员角色。
 */
@RestController
@RequestMapping("/announcement")
@Slf4j
@Tag(name = "AnnouncementController", description = "公告接口")
public class AnnouncementController {
    @Resource
    private AnnouncementService announcementService;

    @Resource
    private UserService userService;

    /**
     * 用户端拉取可见公告。
     * 刻意不加 @AuthCheck：登录页也需要显示「系统维护」这类通告，
     * 而且返回的字段里没有任何敏感信息。
     */
    @GetMapping("/visible")
    @Operation(summary = "获取当前可见公告")
    public BaseResponse<List<AnnouncementVO>> listVisible() {
        return ResultUtils.success(announcementService.listVisible());
    }

    @GetMapping("/admin/list")
    @Operation(summary = "分页查询公告（管理员）")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Announcement>> pageForAdmin(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return ResultUtils.success(announcementService.pageForAdmin(pageNum, pageSize, keyword, status));
    }

    @PostMapping("/admin/save")
    @Operation(summary = "新增或编辑公告（管理员）")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> save(@RequestBody @Valid AnnouncementSaveRequest request,
                                   HttpServletRequest httpRequest) {
        User loginUser = userService.getLoginUser(httpRequest);
        return ResultUtils.success(announcementService.saveAnnouncement(request, loginUser.getId()));
    }

    @PostMapping("/admin/publish/{id}")
    @Operation(summary = "发布公告（管理员）")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> publish(@PathVariable Long id) {
        return ResultUtils.success(announcementService.publish(id));
    }

    @PostMapping("/admin/offline/{id}")
    @Operation(summary = "下线公告（管理员）")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> offline(@PathVariable Long id) {
        return ResultUtils.success(announcementService.offline(id));
    }

    @PostMapping("/admin/delete/{id}")
    @Operation(summary = "删除公告（管理员）")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> delete(@PathVariable Long id) {
        return ResultUtils.success(announcementService.removeAnnouncement(id));
    }
}
