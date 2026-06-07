package com.ai.codeplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ai.codeplatform.ai.AiCodeGenTypeRoutingService;
import com.ai.codeplatform.ai.AiCodeGenTypeRoutingServiceFactory;
import com.ai.codeplatform.constant.AppConstant;
import com.ai.codeplatform.core.AiCodeGeneratorFacade;
import com.ai.codeplatform.manager.CancelGenerationManager;
import com.ai.codeplatform.core.builder.VueProjectBuilder;
import com.ai.codeplatform.core.handler.StreamHandlerExecutor;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.manager.CosManager;
import com.ai.codeplatform.mapper.UserMapper;
import com.ai.codeplatform.model.dto.app.AppAddRequest;
import com.ai.codeplatform.model.dto.app.AppQueryRequest;
import com.ai.codeplatform.model.entity.ChatHistoryOriginal;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.model.enums.ChatHistoryMessageTypeEnum;
import com.ai.codeplatform.model.enums.CodeGenTypeEnum;
import com.ai.codeplatform.model.vo.AppVO;
import com.ai.codeplatform.model.vo.UserVO;
import com.ai.codeplatform.rag.QdrantDocumentLoader;
import com.ai.codeplatform.service.*;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ai.codeplatform.model.entity.App;
import com.ai.codeplatform.mapper.AppMapper;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.ai.codeplatform.constant.AppConstant.CODE_OUTPUT_ROOT_DIR;
import static com.ai.codeplatform.constant.RagConstant.RAG_LOAD_DIRECTORY_PATH;

/**
 * 应用 服务层实现。
 *
 * @author Administrator
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {
    @Resource
    private UserService userService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private CosManager cosManager;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    @Resource
    private CancelGenerationManager cancelGenerationManager;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;

    @Resource
    private ChatHistoryOriginalService chatHistoryOriginalService;

    @Resource
    private QdrantDocumentLoader qdrantDocumentLoader;

    @Value("${code.deploy-path:http://localhost}")
    private String deployHost;

    /**
     * 创建应用
     *
     * @param appAddRequest
     * @param request
     * @param initPrompt
     * @return
     */
    @Override
    @Transactional
    public App createApp(AppAddRequest appAddRequest, HttpServletRequest request, String initPrompt) {
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 构造入库对象
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称暂时为 initPrompt 前 12 位
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // ai智能选择代码生成类型
        AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
        CodeGenTypeEnum selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
        if (selectedCodeGenType == CodeGenTypeEnum.WARNING) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "禁止输入无关的提示词");
        }
        if (selectedCodeGenType == null) {
            selectedCodeGenType = CodeGenTypeEnum.MULTI_FILE;
        }
        app.setCodeGenType(selectedCodeGenType.getValue());
        // 使用 CAS 方式扣减额度（最多重试 3 次）
        int maxRetry = 3;
        boolean success = false;
        for (int i = 0; i < maxRetry; i++) {
            // 重新查询最新的用户信息
            User currentUser = userService.getById(loginUser.getId());
            if (currentUser == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
            }

            int currentQuota = currentUser.getQuota();
            // 检查额度是否充足
            if (currentQuota <= 0) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "额度不足，请充值后再试");
            }

            // CAS 扣减额度
            // update user set quota = quota - 1 where id = ? and quota = ?
            success = userService.updateChain()
                    .set(User::getQuota, currentQuota - 1)
                    .set(User::getUpdateTime, LocalDateTime.now())
                    .where(User::getId).eq(loginUser.getId())
                    .and(User::getQuota).eq(currentQuota)
                    .update();

            if (success) {
                log.info("用户 {} 额度扣减成功，原额度: {}, 新额度: {}", currentUser.getId(), currentQuota, currentQuota - 1);
                break;
            } else {
                log.warn("用户 {} 额度扣减失败（并发冲突），第 {} 次重试", currentUser.getId(), i + 1);
            }
        }

        if (!success) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "系统繁忙，请稍后重试");
        }
        // 插入数据库
        boolean result = save(app);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        log.info("应用创建成功，ID: {}, 类型: {}", app.getId(), selectedCodeGenType.getValue());
        return app;
    }

    /**
     * 聊天生成代码
     *
     * @param appId
     * @param message
     * @param loginUser
     * @return
     */
    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser, MultipartFile[] files) {
        // 1. 参数校验
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        if (StrUtil.isBlank(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        }
        // 2. 查询应用信息
        App app = this.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        // 5.校验用户提示词的合法性（考虑上传的文件）
        AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
        String intentMessage = message;
        if (files != null && files.length > 0) {
            boolean hasImage = false, hasText = false;
            for (MultipartFile f : files) {
                if (f.getSize() > 1024 * 1024 * 5) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "文件大小不能超过 5MB");
                }
                String ct = f.getContentType();
                if (ct != null && ct.startsWith("image/")) hasImage = true;
                else if (isTextFile(f)){
                    hasText = true;
                }
                else{
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "请上传正确的附件！");
                }
            }
            if (hasImage && hasText) intentMessage += " (用户上传了参考图片和需求文档)";
            else if (hasImage) intentMessage += " (用户上传了参考图片)";
            else if (hasText) intentMessage += " (用户上传了需求文档)";
        }
        boolean designRelated = routingService.isDesignRelated(intentMessage);
        if (!designRelated) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "请提供具体的网页修改需求或功能描述。");
        }

        // 6. 构建用户消息内容列表
        List<Content> contents = new ArrayList<>();
        TextContent textContent = new TextContent(message);
        contents.add(textContent);
        // 7.构建入库的用户会话历史
        List<Map<String, Object>> serializableContents = new ArrayList<>();
        Map<String, Object> textMap = new HashMap<>();
        textMap.put("type", "text");
        textMap.put("text", textContent.text());
        serializableContents.add(textMap);
        if (files != null) {
            for (MultipartFile file : files) {
                String contentType = file.getContentType();
                Map<String, Object> contentMap = new HashMap<>();
                if (contentType != null && contentType.startsWith("image/")) {
                    // 图片 → ImageContent
                    String imageUrl = cosManager.putUserImage(loginUser.getId(), file, "chat_photo/" + loginUser.getId());
                    String imageSuffix = imageUrl.substring(imageUrl.lastIndexOf(".") + 1);
                    ImageContent imageContent = new ImageContent(imageUrl);
                    contents.add(imageContent);
                    contentMap.put("type", "image");
                    contentMap.put("mimeType", "image/" + imageSuffix);
                    contentMap.put("url", imageUrl);
                } else if (isTextFile(file)) {
                    // 文本文件 → 读取内容 → TextContent
                    String text = null;
                    try {
                        text = new String(file.getBytes(), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        try {
                            text = new String(file.getBytes(), "GBK");
                        } catch (IOException ex) {
                            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文本文件转换异常");
                        }
                    }
                    contents.add(new TextContent("=== 上传的文件内容 ===\n" + text));
                    if(file.getOriginalFilename() != null){
                        contentMap.put("type", file.getOriginalFilename().toLowerCase().substring(file.getOriginalFilename().lastIndexOf(".")+1));
                    }else{
                        contentMap.put("type", "text");
                    }
                    contentMap.put("text", "=== 上传的文件内容 ===\n" + text);
                }
                serializableContents.add(contentMap);
            }
        }
        // 8. 构造多模态 UserMessage
        UserMessage userMessage = UserMessage.from(contents);
        // 9. 序列化为 JSON 字符串
        String messageStr = JSONUtil.toJsonStr(serializableContents);

        // 10. 通过校验后，添加用户消息到对话历史
        chatHistoryService.addChatMessage(appId, messageStr, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        chatHistoryOriginalService.addOriginalChatMessage(appId, messageStr, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        // 11. 注册取消标志
        cancelGenerationManager.register(appId);
        // 12. 调用 AI 生成代码（流式）
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId);
        // 13. 收集 AI 响应内容并在完成后记录到对话历史
        return streamHandlerExecutor
                .doExecute(codeStream, chatHistoryService, chatHistoryOriginalService, appId, loginUser, codeGenTypeEnum)
                .doFinally(signalType -> cancelGenerationManager.remove(appId));
    }

    /**
     * 判断文件是否为文本类型文件
     */
    private boolean isTextFile(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) {
            return false;
        }
        // 通过文件扩展名判断
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
            String lowerName = originalFilename.toLowerCase();
            return lowerName.endsWith(".md") ||
                    lowerName.endsWith(".markdown") ||
                    lowerName.endsWith(".txt");
        }
        return false;
    }

    /**
     * 获取应用视图对象,并封装用户脱敏信息
     *
     * @param app
     * @return
     */
    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 填充部署访问地址
        if (StrUtil.isNotBlank(app.getDeployKey())) {
            appVO.setDeployUrl(String.format("%s/%s/", deployHost, app.getDeployKey()));
        }
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }

    /**
     * 获取查询条件
     *
     * @param appQueryRequest
     * @return
     */
    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String searchCategory = appQueryRequest.getCategory();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .eq("category", StrUtil.isNotBlank(searchCategory) ? searchCategory : null)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }

    /**
     * 获取应用视图对象列表
     *
     * @param appList
     * @return
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }

    /**
     * 应用部署
     *
     * @param appId
     * @param loginUser
     * @return
     */
    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        }
        // 2. 查询应用信息
        App app = this.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        // 3. 验证用户是否有权限部署该应用，仅本人可以部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 4. 检查是否已有 deployKey
        String deployKey = app.getDeployKey();
        // 没有则生成 6 位 deployKey（大小写字母 + 数字）
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 获取代码生成类型，构建源目录路径
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 6. 检查源目录是否存在
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }
        // 7. Vue 项目特殊处理：执行构建
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            // Vue 项目需要构建
            boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
            if (!buildSuccess) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            }
            // 检查 dist 目录是否存在
            File distDir = new File(sourceDirPath, "dist");
            if (!distDir.exists()) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            }
            // 将 dist 目录作为部署源
            sourceDir = distDir;
            log.info("Vue 项目构建成功，将部署 dist 目录: {}", distDir.getAbsolutePath());
        }
        // 8. 复制文件到部署目录和知识库目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);

            String filePath = RAG_LOAD_DIRECTORY_PATH + File.separator + codeGenType + "_" + appId;
            if (!new File(filePath).exists()) {
                Thread.startVirtualThread(() ->{
                    try {
                        // 把部署文件复制到知识库目录
                        FileUtil.copyContent(new File(sourceDirPath), new File(filePath), true);
                        // 对部署文件做向量转化和存储
                        qdrantDocumentLoader.loadDocuments(filePath);
                        log.info("向量转换成功，转换目录: {}", filePath);
                    } catch (IORuntimeException e) {
                        log.error("向量转换失败，转换目录: {}", filePath);
                    }
                });
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }
        // 9. 更新应用的 deployKey 和部署时间
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        if (!updateResult) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        }
        // 10. 构建应用访问 URL
        String appDeployUrl = String.format("%s/%s/", deployHost, deployKey);
        // 11. 异步生成截图并更新应用封面
        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;
    }


    /**
     * 使用app模板
     *
     * @param templateId 模板ID
     * @return 新应用ID
     */
    @Override
    public Long forkTemplate(Long templateId, User loginUser) {
        App templateApp = getById(templateId);
        QueryWrapper queryWrapper = QueryWrapper.create().eq(ChatHistoryOriginal::getAppId, templateId);
        List<ChatHistoryOriginal> chatHistoryOriginals = chatHistoryOriginalService.list(queryWrapper);
        if (chatHistoryOriginals.isEmpty()) {
            log.error("模板没有会话历史，appId：{}", templateApp);
        }
        if (templateApp == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "模板不存在");
        }
        if (templateApp.getPriority() != 99) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该应用不是模板，无法使用");
        }
        App app = new App();
        BeanUtil.copyProperties(templateApp, app);
        app.setInitPrompt(null);
        app.setId(null);
        app.setUserId(loginUser.getId());
        app.setCover(null);
        app.setEditTime(LocalDateTime.now());
        app.setCreateTime(LocalDateTime.now());
        app.setDeployKey(null);
        app.setDeployedTime(null);
        app.setPriority(0);
        boolean isSuccess = save(app);
        if (!isSuccess) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模板使用失败");
        }
        for (ChatHistoryOriginal h : chatHistoryOriginals) {
            h.setId(null);
            h.setAppId(app.getId());
            h.setUserId(loginUser.getId());
            h.setCreateTime(LocalDateTime.now());
            h.setUpdateTime(LocalDateTime.now());
        }
        // 批量复制会话历史
        chatHistoryOriginalService.saveBatch(chatHistoryOriginals);
        try {
            FileUtil.copyContent(new File(CODE_OUTPUT_ROOT_DIR +
                            File.separator + templateApp.getCodeGenType() + "_" + templateApp.getId()),
                    new File(CODE_OUTPUT_ROOT_DIR +
                            File.separator + app.getCodeGenType() + "_" + app.getId()), true);
        } catch (IORuntimeException e) {
            log.error("模板文件复制失败：{}", e.getMessage());
        }
        return app.getId();
    }

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 使用虚拟线程异步执行
        Thread.startVirtualThread(() -> {
            // 调用截图服务生成截图并上传
            String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
            // 更新应用封面字段
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setCover(screenshotUrl);
            boolean updated = this.updateById(updateApp);
            if (!updated) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
            }
        });
    }

    /**
     * 删除应用时关联删除对话历史
     *
     * @param id 应用ID
     * @return 是否成功
     */
    @Override
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        // 转换为 Long 类型
        Long appId = Long.valueOf(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删除关联的对话历史
        try {
            chatHistoryService.deleteByAppId(appId);
            chatHistoryOriginalService.deleteByAppId(appId);
        } catch (Exception e) {
            // 记录日志但不阻止应用删除
            log.error("删除应用关联对话历史失败: {}", e.getMessage());
        }
        // 删除应用
        return super.removeById(id);
    }


}
