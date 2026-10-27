package com.ai.codeplatform.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.ai.AppPlanService;
import com.ai.codeplatform.ai.model.plan.PlanListResult;
import com.ai.codeplatform.annotation.AuthCheck;
import com.ai.codeplatform.annotation.LogRecord;
import com.ai.codeplatform.common.BaseResponse;
import com.ai.codeplatform.common.DeleteRequest;
import com.ai.codeplatform.common.ResultUtils;
import com.ai.codeplatform.constant.AppConstant;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.manager.CancelGenerationManager;
import com.ai.codeplatform.manager.CosManager;
import com.ai.codeplatform.model.dto.app.*;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.model.enums.ModelEnum;
import com.ai.codeplatform.model.vo.AppVO;
import com.ai.codeplatform.rag.RagSwitchHolder;
import com.ai.codeplatform.ratelimiter.annotation.RateLimit;
import com.ai.codeplatform.ratelimiter.enums.RateLimitType;
import com.ai.codeplatform.service.*;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import com.ai.codeplatform.model.entity.App;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 应用 控制层。
 *
 * @author Administrator
 */
@Slf4j
@RestController
@RequestMapping("/app")
public class AppController {
    private static final ThreadLocal<Boolean> threadLocal = new ThreadLocal<>();

    /** 换方案时最多带几个"已看过"的方案标题给模型，太多会挤占 prompt 且容易让它混乱 */
    private static final int MAX_EXCLUDE_TITLES = 8;

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Resource
    private CancelGenerationManager cancelGenerationManager;

    @Resource
    private CosManager cosManager;

    @Resource
    private AppPlanService appPlanService;

    /**
     * 下载应用代码
     *
     * @param appId    应用ID
     * @param request  请求
     * @param response 响应
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        // 1. 基础校验
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID无效");
        }
        // 2. 查询应用信息
        App app = appService.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 3. 权限校验：只有应用创建者可以下载代码
        User loginUser = userService.getLoginUser(request);
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        }
        // 4. 构建应用代码目录路径（生成目录，非部署目录）
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 5. 检查代码目录是否存在
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        }
        // 6. 生成下载文件名（不建议添加中文内容）
        String downloadFileName = String.valueOf(appId);
        // 7. 调用通用下载服务
        projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFileName, response);
    }

    /**
     * 创建应用
     *
     * @param appAddRequest 创建应用请求
     * @param request       请求
     * @return 应用 id
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @LogRecord(description = "创建APP应用")
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI对话请求过于频繁，请稍后再试")
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        if (appAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 参数校验
        String initPrompt = appAddRequest.getInitPrompt();
        if (StrUtil.isBlank(initPrompt)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        }
        App app = appService.createApp(appAddRequest, request, initPrompt);
        return ResultUtils.success(app.getId());
    }

    /**
     * 生成设计方案（创建应用前的方案预选）。
     * 与 {@link #addApp} 刻意分离：这一步只产出方案，**不创建应用、不扣额度、不写任何文件**。
     * 前端拿到方案后让用户选择或自定义，选择结果拼进 prompt，再走 /add + /chat/gen/code。
     * 这样做的原因：把「方案确认」做成两个独立的 HTTP 请求，状态天然无状态，
     * 用户关掉页面再回来也不会卡在半截的 SSE 连接上。
     *
     * @param appPlanRequest 应用描述
     * @return 方案列表
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @RateLimit(limitType = RateLimitType.USER, rate = 20, rateInterval = 60, message = "方案生成过于频繁，请稍后再试")
    @PostMapping("/chat/plan")
    public BaseResponse<PlanListResult> generateAppPlan(@RequestBody AppPlanRequest appPlanRequest) {
        if (appPlanRequest == null || StrUtil.isBlank(appPlanRequest.getInitPrompt())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用描述不能为空");
        }
        PlanListResult result;
        try {
            result = appPlanService.generatePlans(buildPlanPrompt(appPlanRequest));
        } catch (Exception e) {
            // 方案预选是增强能力，失败不能让用户卡住：返回空方案，前端会直接进入生成流程
            log.warn("方案生成失败，降级为直接生成。prompt: {}, 原因: {}",
                    StrUtil.maxLength(appPlanRequest.getInitPrompt(), 100), e.toString());
            return ResultUtils.success(new PlanListResult());
        }
        if (result == null || result.getPlans() == null || result.getPlans().isEmpty()) {
            return ResultUtils.success(new PlanListResult());
        }
        // 需求已明确、或压根不是前端需求时，只保留一个方案，避免出现"伪选择"
        boolean skipChoice = Boolean.TRUE.equals(result.getRecommendSkip())
                || Boolean.TRUE.equals(result.getIrrelevant());
        if (skipChoice && result.getPlans().size() > 1) {
            result.setPlans(result.getPlans().subList(0, 1));
        }
        return ResultUtils.success(result);
    }

    /**
     * 把「已看过的方案」拼进给模型的用户消息。
     * <p>
     * 不做成 {@code AppPlanService} 的第二个参数，是因为 langchain4j 对参数 ≥ 2 的方法
     * 要求每个参数都带 {@code @UserMessage/@V/@MemoryId} 之类注解，为一个纯文本约束
     * 去改接口签名并不划算。
     */
    private String buildPlanPrompt(AppPlanRequest appPlanRequest) {
        String prompt = appPlanRequest.getInitPrompt().trim();
        List<String> excludeTitles = appPlanRequest.getExcludeTitles();
        if (excludeTitles == null || excludeTitles.isEmpty()) {
            return prompt;
        }
        String joined = excludeTitles.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .limit(MAX_EXCLUDE_TITLES)
                .collect(Collectors.joining("、"));
        if (StrUtil.isBlank(joined)) {
            return prompt;
        }
        return prompt + "\n\n【本次需避开的方向】\n" + joined
                + "\n以上方向用户已经看过或已经采用过，请给出布局结构与交互模式明显不同的新方案。";
    }

    /**
     * 换方案：清空当前应用的生成产物，让下一轮从零开始。
     * <p>
     * 只删 {@code code_output} 下的工作目录，<b>不动 code_deploy</b> —— 已部署的线上版本
     * 删掉就是站点直接 404，而用户只是想换个方向重做，并没打算把应用下线。
     * <p>
     * 目录名由 {@code codeGenType + "_" + appId} 派生，不接受客户端传路径；
     * 另外再做一次 canonicalPath 校验，防止拼接结果意外越出工作区。
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @RateLimit(limitType = RateLimitType.USER, rate = 10, rateInterval = 60, message = "操作过于频繁，请稍后再试")
    @PostMapping("/chat/restart/{appId}")
    public BaseResponse<Boolean> restartGeneration(@PathVariable Long appId, HttpServletRequest request) {
        // 1. 校验应用存在且属于当前用户
        App app = appService.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        User loginUser = userService.getLoginUser(request);
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        // 2. 清掉这个应用的所有生成目录
        //    不按 codeGenType 拼路径：用户可能切换过生成类型（html / multi_file / vue_project），
        //    只清当前类型会留下旧产物，新方案写文件时又撞上旧文件。
        //    目录名固定为 "{类型}_{appId}"，所以按最后一个下划线之后的部分精确匹配 appId。
        File outputRoot = new File(AppConstant.CODE_OUTPUT_ROOT_DIR);
        File[] appDirs = outputRoot.listFiles(File::isDirectory);
        String appIdSuffix = String.valueOf(appId);
        int deleted = 0;
        if (appDirs != null) {
            for (File dir : appDirs) {
                int separator = dir.getName().lastIndexOf('_');
                if (separator < 0 || !dir.getName().substring(separator + 1).equals(appIdSuffix)) {
                    continue;
                }
                // 双保险：确认目标确实落在工作区之内
                try {
                    String rootPath = outputRoot.getCanonicalPath() + File.separator;
                    if (!dir.getCanonicalPath().startsWith(rootPath)) {
                        continue;
                    }
                } catch (IOException e) {
                    log.warn("换方案：跳过目录校验失败的路径 {}", dir, e);
                    continue;
                }
                if (!FileUtil.del(dir)) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "清理生成目录失败，请稍后重试");
                }
                deleted++;
            }
        }
        log.info("换方案：已清空应用 {} 的生成目录，共 {} 个", appId, deleted);
        return ResultUtils.success(true);
    }

    /**
     * 获取模型列表
     *
     * @return 模型列表
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @GetMapping("/model/list")
    public BaseResponse<Map<String,Object>> modelList(){
        Map<String, Object> modelMap = new HashMap<>();
        ModelEnum grok = ModelEnum.getEnumByProvider("grok");
        ModelEnum deepseek = ModelEnum.getEnumByProvider("deepseek");
        ModelEnum chatgpt = ModelEnum.getEnumByProvider("chatgpt");
        // 将模型信息添加到 map 中（default 为布尔值，标识默认模型）
        if (deepseek != null) {
            modelMap.put("deepseek", Map.<String, Object>of("modelName", deepseek.getModelName(), "label", deepseek.getLabel(), "default", true));
        }
        if (grok != null) {
            modelMap.put("grok", Map.<String, Object>of("modelName", grok.getModelName(), "label", grok.getLabel(), "default", false));
        }
        if (chatgpt != null) {
            modelMap.put("chatgpt", Map.<String, Object>of("modelName", chatgpt.getModelName(), "label", chatgpt.getLabel(), "default", false));
        }
        return ResultUtils.success(modelMap);
    }

    /**
     * 聊天生成代码
     *
     * @param appId   应用ID
     * @param message 用户消息
     * @param request 请求
     * @return 生成的代码
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @LogRecord(description = "开始生成代码")
    @PostMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       @RequestParam(required = false) String modelName,
                                                       @RequestParam(required = false) Boolean useRag,
                                                       @RequestPart(value = "files",required = false)
                                                       MultipartFile[] files,
                                                       HttpServletRequest request) {
        // 保存Rag使用状态
        RagSwitchHolder.set(useRag != null ? useRag : false);
        // 参数校验：未指定模型时默认使用 DeepSeek（兼容未传 modelName 的调用方）
        if (StrUtil.isBlank(modelName)) {
            modelName = ModelEnum.DEEP_SEEK.getModelName();
        }
        if (ModelEnum.getEnumByModelName(modelName) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模型名称无效");
        }
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用ID无效");
        }
        if (StrUtil.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        }
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务生成代码（流式）
        Flux<String> contentFlux = appService.chatToGenCode(appId, message,modelName ,loginUser,files);
        // 转换为 ServerSentEvent 格式
        return contentFlux
                .map(chunk -> {
                    // 将内容包装成JSON对象
                    Map<String, String> wrapper = Map.of("d", chunk);
                    String jsonData = JSONUtil.toJsonStr(wrapper);
                    return ServerSentEvent.<String>builder()
                            .data(jsonData)
                            .build();
                })
                .concatWith(Mono.just(
                        // 发送结束事件
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));
    }

    /**
     * 取消代码生成
     * @param appId   应用ID
     * @param request 请求
     * @return 取消结果
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @PostMapping("/cancel/{appId}")
    public BaseResponse<Boolean> cancel(@PathVariable Long appId,HttpServletRequest  request) {
        // 1. 校验应用是否存在
        App app = appService.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 2. 校验当前用户是否是应用创建者
        User loginUser = userService.getLoginUser(request);
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 3. 取消代码生成
        cancelGenerationManager.cancel(appId);
        return ResultUtils.success(true);
    }


    /**
     * 更新应用（用户只能更新自己的应用名称）
     *
     * @param appUpdateRequest 更新请求
     * @param request          请求
     * @return 更新结果
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        if (appUpdateRequest == null || appUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = appUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        if (oldApp == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人可更新
        if (!oldApp.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        return ResultUtils.success(true);
    }

    /**
     * 删除应用（用户只能删除自己的应用）
     *
     * @param deleteRequest 删除请求
     * @param request       请求
     * @return 删除结果
     */
    @LogRecord(description = "用户删除应用")
    @CacheEvict(value = "good_app_page", allEntries = true)
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        if (oldApp == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 仅本人或管理员可删除
        if (!oldApp.getUserId().equals(loginUser.getId()) && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = appService.removeById(id);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "删除应用失败");
        }
        // 异步清理应用占用的资源（本地目录、部署目录、COS 封面）
        Thread.ofVirtual().start(() -> cleanupAppResources(oldApp));
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 查询数据库
        App app = appService.getById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 获取封装类（包含用户信息）
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 分页获取当前用户创建的应用列表
     *
     * @param appQueryRequest 查询请求
     * @param request         请求
     * @return 应用列表
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        if (pageSize > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long pageNum = appQueryRequest.getPageNum();
        // 只查询当前用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @Cacheable(
            value = "good_app_page",
            key = "T(com.ai.codeplatform.utils.CacheKeyUtils).generateKey(#appQueryRequest)",
            condition = "#appQueryRequest.pageNum <= 10"
    )
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        if (pageSize > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        }
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 管理员删除应用
     *
     * @param deleteRequest 删除请求
     * @return 删除结果
     */
    @CacheEvict(value = "good_app_page", allEntries = true)
    @LogRecord(description = "管理员删除应用")
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = deleteRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        if (oldApp == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        boolean result = appService.removeById(id);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用删除失败");
        }
        // 异步清理应用占用的资源（本地目录、部署目录、COS 封面）
        Thread.ofVirtual().start(() -> cleanupAppResources(oldApp));
        return ResultUtils.success(result);
    }

    /**
     * 管理员更新应用
     *
     * @param appAdminUpdateRequest 更新请求
     * @return 更新结果
     */
    @CacheEvict(value = "good_app_page", allEntries = true)
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        if (appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = appAdminUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        if (oldApp == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        return ResultUtils.success(true);
    }

    /**
     * 管理员分页获取应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 应用列表
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 管理员根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 查询数据库
        App app = appService.getById(id);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        // 获取封装类
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 使用app模板（精选应用）
     * @param templateId 模板ID
     * @return 创建结果
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @PostMapping("/template/fork")
    public BaseResponse<Long> forkTemplate(@RequestParam Long templateId, HttpServletRequest request) {
        if (templateId == null || templateId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.forkTemplate(templateId, loginUser);
        return ResultUtils.success(appId);
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        if (appDeployRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long appId = appDeployRequest.getAppId();
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 清理应用占用的外部资源：本地输出目录、部署目录、COS 上的封面截图。
     *
     * @param app 删除前的应用快照（删完库之后 codeGenType / deployKey 就拿不到了）
     */
    private void cleanupAppResources(App app) {
        // 1. 删除本地应用目录
        String dirName = app.getCodeGenType() + "_" + app.getId();
        String outputDir = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + dirName;
        boolean exist = FileUtil.exist(new File(outputDir));
        if (!exist) {
            log.info("不存在的应用输出目录：{}", outputDir);
        } else {
            boolean del = FileUtil.del(outputDir);
            if (!del) {
                log.warn("删除应用输出目录失败，应用目录：{}", outputDir);
            } else {
                log.info("删除应用输出目录成功，应用目录：{}", outputDir);
            }
        }
        // 2. 删除部署目录
        if (app.getDeployKey() == null) {
            log.info("应用未部署，不存在部署密钥,不需要删除部署目录");
        } else {
            String deployDir = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + app.getDeployKey();
            boolean isExist = FileUtil.exist(new File(deployDir));
            if (!isExist) {
                log.info("不存在的应用部署目录：{}", deployDir);
            } else {
                boolean resultDel = FileUtil.del(deployDir);
                if (!resultDel) {
                    log.warn("删除应用部署文件失败，部署目录：{}", deployDir);
                } else {
                    log.info("删除应用部署文件成功，部署目录：{}", deployDir);
                }
            }
        }
        // 3. 删除 COS 上的应用封面截图。
        String cover = app.getCover();
        if (StrUtil.isBlank(cover)) {
            log.info("应用无封面，跳过 COS 清理，应用ID：{}", app.getId());
        } else if (cosManager.deleteObjectByUrl(cover)) {
            log.info("删除应用封面成功，应用ID：{}，封面：{}", app.getId(), cover);
        } else {
            log.warn("删除应用封面失败，应用ID：{}，封面：{}", app.getId(), cover);
        }
    }

    /**
     * 获取个人用户应用的统计信息
     * @param request 请求
     * @return 统计信息
     */
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    @GetMapping("/my/statistics")
    public BaseResponse<Map<String, Object>> getMyStatistics(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);

        // 查询用户的应用总数
        long totalApps = appService.queryChain()
                .eq(App::getUserId, loginUser.getId())
                .count();

        // 查询已部署的应用数
        long deployedApps = appService.queryChain()
                .eq(App::getUserId, loginUser.getId())
                .isNotNull(App::getDeployKey)
                .count();

        return ResultUtils.success(Map.of(
                "totalApps", totalApps,
                "deployedApps", deployedApps,
                "remainingQuota", loginUser.getQuota()
        ));
    }

}

