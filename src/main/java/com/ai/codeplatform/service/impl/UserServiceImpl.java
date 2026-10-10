package com.ai.codeplatform.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.exception.BusinessException;
import com.ai.codeplatform.exception.ErrorCode;
import com.ai.codeplatform.model.dto.user.UserQueryRequest;
import com.ai.codeplatform.model.enums.UserRoleEnum;
import com.ai.codeplatform.model.enums.VipEnum;
import com.ai.codeplatform.model.vo.LoginUserVO;
import com.ai.codeplatform.model.vo.UserVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.mapper.UserMapper;
import com.ai.codeplatform.service.UserService;
import com.ai.codeplatform.service.email.EmailCodeService;
import com.ai.codeplatform.utils.EmailUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.ai.codeplatform.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户 服务层实现。
 *
 * @author Administrator
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>  implements UserService{

    @Resource
    private EmailCodeService emailCodeService;

    /**
     * 用户注册（邮箱必填，需通过邮箱验证码校验）
     *
     * @param userAccount   用户账户（不允许包含 @，避免与邮箱登录撞号）
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @param email         邮箱（原始输入，内部做归一化与白名单校验）
     * @param emailCode     邮箱验证码
     * @return 新用户 id
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword,
                             String email, String emailCode) {
        // 1. 基础校验
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword, email, emailCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        // 账号不允许含 @：userAccount 与 email 是两个独立的唯一索引，跨列并不互斥。
        // 如果某人的账号恰好等于另一个人的邮箱，登录时 userAccount=? OR email=? 会命中两行。
        if (userAccount.contains("@")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号不能包含 @");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        // 2. 邮箱归一化 + 白名单校验
        String normalizedEmail = emailCodeService.validateAndNormalize(email);
        // 3. 账号查重
        long accountCount = this.mapper.selectCountByQuery(
                new QueryWrapper().eq("userAccount", userAccount));
        if (accountCount > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
        // 4. 邮箱查重（唯一索引是最终兜底，这里先给出友好提示）
        long emailCount = this.mapper.selectCountByQuery(
                new QueryWrapper().eq("email", normalizedEmail));
        if (emailCount > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该邮箱已被注册");
        }
        // 5. 校验邮箱验证码。放在最后：前面几步失败时不会白白消耗掉一次验证码
        emailCodeService.verifyCode(EmailCodeService.SCENE_REGISTER, normalizedEmail, emailCode);
        // 6. 加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 7. 插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setEmail(normalizedEmail);
        // 注册时已验证过验证码，所以直接标记为已验证
        user.setEmailVerified(1);
        user.setVipLevel(VipEnum.NORMAL.getValue());
        user.setQuota(5);
        user.setUserPassword(encryptPassword);
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
        }
        return user.getId();
    }
    /**
     * 获取加密密码
     * @param userPassword
     * @return
     */
    @Override
    public String getEncryptPassword(String userPassword) {
        // 盐值，混淆密码
        final String SALT = "salt";
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
    }

    /**
     * 获取脱敏的登录用户信息
     * @param user
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    /**
     * 用户登录
     * @param account   用户账户
     * @param userPassword  用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    @Override
    public LoginUserVO userLogin(String account, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StrUtil.hasBlank(account, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (account.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }
        // 2. 加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 3. 按「账号或邮箱」查询。
        //    这里刻意用 if/else 分流，而不是 userAccount=? OR email=?：
        //    userAccount 与 email 是两个独立的唯一索引，跨列并不互斥，
        //    OR 在极端数据组合下会命中两行，导致登进别人的账号。
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userPassword", encryptPassword);
        if (account.contains("@")) {
            String normalizedEmail = EmailUtils.normalize(account);
            if (normalizedEmail == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
            }
            // 只有经过本站验证的邮箱才允许用于登录（OAuth 带回等未验证来源一律不可登录）
            queryWrapper.eq("email", normalizedEmail);
            queryWrapper.eq("emailVerified", 1);
        } else {
            queryWrapper.eq("userAccount", account);
        }
        User user = this.mapper.selectOneByQuery(queryWrapper);
        // 用户不存在
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 4. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);
        // 5. 获得脱敏后的用户信息
        return this.getLoginUserVO(user);
    }

    /**
     * 逻辑删除用户。删除前改写 email，释放唯一索引。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteUserLogically(Long userId) {
        User user = this.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        String originEmail = user.getEmail();
        if (StrUtil.isNotBlank(originEmail)) {
            String tombstone = buildTombstoneEmail(originEmail, userId);
            boolean updated = this.updateChain()
                    .set(User::getEmail, tombstone)
                    // 墓碑邮箱不可信，顺带把验证标记清掉
                    .set(User::getEmailVerified, 0)
                    .where(User::getId).eq(userId)
                    .update();
            if (!updated) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户删除失败");
            }
            log.info("逻辑删除用户 {}，邮箱已改写以释放唯一索引: {} -> {}", userId, originEmail, tombstone);
        }
        return this.removeById(userId);
    }

    /**
     * 构造墓碑邮箱：{原local}_deleted_{userId}@{原domain}
     * 保留原域名便于事后统计，同时把 userId 写进去保证全局唯一。
     * email 列是 varchar(256)，local 部分按可用长度截断；域名过长时退回保留域名。
     */
    private String buildTombstoneEmail(String email, Long userId) {
        String suffix = "_deleted_" + userId;
        int at = email.lastIndexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        String domain = at > 0 ? email.substring(at + 1) : "deleted.invalid";
        // 256 - 后缀 - '@' - 域名，得到 local 可用长度
        int maxLocalLength = 256 - suffix.length() - 1 - domain.length();
        if (maxLocalLength < 1) {
            // 域名本身太长，放弃保留原域名，改用保留域名（RFC 2606 的 .invalid）
            return "deleted_" + userId + "@deleted.invalid";
        }
        if (local.length() > maxLocalLength) {
            local = local.substring(0, maxLocalLength);
        }
        return local + suffix + "@" + domain;
    }

    /**
     * 获取当前登录用户
     * @param request
     * @return
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 从数据库查询（追求性能的话可以注释，直接返回上述结果）
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 退出登录
     * @param request
     * @return
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    /**
     * 获取脱敏的用户信息
     * @param user
     * @return
     */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }
    /**
     * 获取脱敏的用户信息列表
     * @param userList
     * @return
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    /**
     * 获取查询条件
     * @param userQueryRequest
     * @return
     */
    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        String vip = userQueryRequest.getVipLevel();
        return QueryWrapper.create()
                .eq("vipLevel", vip)
                .eq("id", id)
                .eq("userRole", userRole)
                .like("userAccount", userAccount)
                .like("userName", userName)
                .like("userProfile", userProfile)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }


}
