package com.ai.codeplatform.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.annotation.AuthCheck;
import com.ai.codeplatform.common.BaseResponse;
import com.ai.codeplatform.common.DeleteRequest;
import com.ai.codeplatform.common.ResultUtils;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.manager.CosManager;
import com.ai.codeplatform.model.dto.user.*;
import com.ai.codeplatform.model.vo.LoginUserVO;
import com.ai.codeplatform.model.vo.UserVO;
import com.ai.codeplatform.ratelimiter.annotation.RateLimit;
import com.ai.codeplatform.ratelimiter.enums.RateLimitType;
import com.mybatisflex.core.paginate.Page;
import com.wf.captcha.SpecCaptcha;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.service.UserService;
import com.ai.codeplatform.service.email.EmailCodeService;
import com.ai.codeplatform.utils.IpUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户 控制层。
 *
 * @author Administrator
 */
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private CosManager cosManager;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private EmailCodeService emailCodeService;

    /**
     * 发送邮箱验证码（用于注册 / 找回密码 / 绑定邮箱 / 额度升级）
     * 必须携带图形验证码：否则本接口会被当成免费发信炮台。
     * @param request 发送请求
     * @param httpServletRequest 用于取真实客户端 IP 做配额控制
     * @return 是否发送成功
     */
    @PostMapping("/email/sendCode")
    @RateLimit(limitType = RateLimitType.IP, rate = 10, rateInterval = 60, message = "验证码发送过于频繁，请稍后再试")
    public BaseResponse<Boolean> sendEmailCode(@RequestBody EmailSendCodeRequest request,
                                               HttpServletRequest httpServletRequest) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String captchaKey = request.getCaptchaKey();
        try {
            if (StrUtil.isBlank(captchaKey)) {
                throw new BusinessException(ErrorCode.CAPTCHA_ERROR, "请先获取图形验证码");
            }
            String cacheCode = stringRedisTemplate.opsForValue().get("captcha:" + captchaKey);
            if (cacheCode == null || !cacheCode.equalsIgnoreCase(StrUtil.trim(request.getCaptchaCode()))) {
                throw new BusinessException(ErrorCode.CAPTCHA_ERROR);
            }
            // 场景缺省为注册，兼容前端不传的情况
            String scene = StrUtil.isBlank(request.getScene())
                    ? EmailCodeService.SCENE_REGISTER : request.getScene();
            // 先归一化 + 白名单校验，域名不支持时不必白白发一封邮件
            String email = emailCodeService.validateAndNormalize(request.getEmail());
            emailCodeService.sendCode(scene, email, IpUtils.getClientIp(httpServletRequest));
            return ResultUtils.success(true);
        } finally {
            // 与登录验证码一致：一次性，无论成败都作废
            if (StrUtil.isNotBlank(captchaKey)) {
                try {
                    stringRedisTemplate.delete("captcha:" + captchaKey);
                } catch (Exception e) {
                    log.warn("删除图形验证码失败（不影响业务）, captchaKey: {}", captchaKey, e);
                }
            }
        }
    }

    /**
     * 用户注册
     *
     * @param userRegisterRequest 用户注册请求
     * @return 注册结果
     */
    @PostMapping("register")
    @RateLimit(limitType = RateLimitType.IP, rate = 10, rateInterval = 60, message = "注册过于频繁，请稍后再试")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        if (userRegisterRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        String email = userRegisterRequest.getEmail();
        String emailCode = userRegisterRequest.getEmailCode();
        long result = userService.userRegister(userAccount, userPassword, checkPassword, email, emailCode);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录
     * @param userLoginRequest 用户登录请求
     * @param request          请求
     * @return 登录结果
     */
    @PostMapping("/login")
    @RateLimit(limitType = RateLimitType.IP, rate = 10, rateInterval = 60, message = "登录尝试过于频繁，请稍后再试")
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        String captchaKey = null;
        try {
            if (userLoginRequest == null || ObjUtil.hasEmpty(userLoginRequest)){
                throw new BusinessException(ErrorCode.PARAMS_ERROR);
            }
            // 获取验证码参数
            captchaKey = userLoginRequest.getCaptchaKey();
            String cacheCode = stringRedisTemplate.opsForValue().get("captcha:" + captchaKey);
            String requestCaptchaCode = userLoginRequest.getCaptchaCode();
            if (cacheCode == null || !cacheCode.equalsIgnoreCase(requestCaptchaCode)) {
                // 用独立错误码，前端可按 code 判断（不再靠文案正则匹配）
                throw new BusinessException(ErrorCode.CAPTCHA_ERROR);
            }
            String userAccount = userLoginRequest.getUserAccount();
            String userPassword = userLoginRequest.getUserPassword();
            LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);
            return ResultUtils.success(loginUserVO);
        } finally {
            // 验证码一次性：校验通过后无论登录成败都作废，避免同一个验证码在 5 分钟 TTL 内被用于密码爆破。
            if (StrUtil.isNotBlank(captchaKey)) {
                try {
                    stringRedisTemplate.delete("captcha:" + captchaKey);
                } catch (Exception e) {
                    log.warn("删除验证码失败（不影响登录结果）, captchaKey: {}", captchaKey, e);
                }
            }
        }
    }

    /**
     * 生成验证码
     * @return
     */
    @PostMapping("/getCaptcha")
    public BaseResponse<Map<String, String>> getCaptcha(@RequestParam(required = false) String captchaKey) {
        // 删除旧验证码
        if (captchaKey != null){
            stringRedisTemplate.delete("captcha:" + captchaKey);
        }
        SpecCaptcha captcha = new SpecCaptcha(130, 48, 4);
        String genCaptchaKey = IdUtil.fastSimpleUUID();
        stringRedisTemplate.opsForValue().set(
                "captcha:" + genCaptchaKey, captcha.text(), 5, TimeUnit.MINUTES);
        return ResultUtils.success(Map.of(
                "captchaKey", genCaptchaKey,
                "captchaImage", captcha.toBase64()
        ));
    }

    /**
     * 获取当前登录用户
     * @param request
     * @return
     */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userService.getLoginUserVO(loginUser));
    }

    /**
     * 用户退出登录
     * @param request
     * @return
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        if (request == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 创建用户
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {
        if (userAddRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = new User();
        BeanUtil.copyProperties(userAddRequest, user);
        // 默认密码 12345678
        final String DEFAULT_PASSWORD = "12345678";
        // 为新用户默认密码加盐
        String encryptPassword = userService.getEncryptPassword(DEFAULT_PASSWORD);
        user.setUserPassword(encryptPassword);
        boolean result = userService.save(user);
        if (!result){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        return ResultUtils.success(user.getId());
    }

    /**
     * 根据 id 获取用户（仅管理员）
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        if (id <= 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getById(id);
        if (user == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取包装类
     */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(long id) {
        BaseResponse<User> response = getUserById(id);
        User user = response.getData();
        return ResultUtils.success(userService.getUserVO(user));
    }

    /**
     * 删除用户
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getById(deleteRequest.getId());
        if (user == null){
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"用户不存在");
        }
        if (user.getUserRole().equals(UserConstant.ADMIN_ROLE)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "非法的删除请求！");
        }
        // 走 Service：逻辑删除前会改写 email，避免该邮箱永久占用唯一索引
        boolean b = userService.deleteUserLogically(deleteRequest.getId());
        if (!b){
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户删除失败");
        }
        return ResultUtils.success(b);
    }

    /**
     * 修改用户信息（管理员）
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = new User();
        BeanUtil.copyProperties(userUpdateRequest, user);
        boolean result = userService.updateById(user);
        if (!result){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        return ResultUtils.success(true);
    }

    /**
     * 修改用户信息(用户)
     */
    @PostMapping("/update/my")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Boolean> updateUserBySelf(UserUpdateRequest userUpdateRequest
            , HttpServletRequest request,@RequestPart(value = "file", required = false) MultipartFile file) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 判断修改用户信息的是否是自己本身
        if (!userUpdateRequest.getId().equals(userService.getLoginUser(request).getId())){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        User user = User.builder()
                .id(userUpdateRequest.getId())
                .userName(userUpdateRequest.getUserName())
                .userProfile(userUpdateRequest.getUserProfile())
                .build();
        // 上传头像
        String userAvatar = cosManager.putUserImage(user.getId(), file,"avatar");
        if(userAvatar != null){
            user.setUserAvatar(userAvatar);
        }
        boolean result = userService.updateById(user);
        if(!result){
            throw new BusinessException(ErrorCode.OPERATION_ERROR);
        }
        return ResultUtils.success(true);
    }


    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 分页参数
        long pageNum = userQueryRequest.getPageNum();
        long pageSize = userQueryRequest.getPageSize();
        // 查询用户列表
        Page<User> userPage = userService.page(Page.of(pageNum, pageSize),
                userService.getQueryWrapper(userQueryRequest));
        // 数据脱敏
        Page<UserVO> userVOPage = new Page<>(pageNum, pageSize, userPage.getTotalRow());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }


}
