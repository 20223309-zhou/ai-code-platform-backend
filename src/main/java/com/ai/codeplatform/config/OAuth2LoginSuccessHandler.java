package com.ai.codeplatform.config;

import cn.hutool.core.util.StrUtil;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@ConfigurationProperties(prefix = "frontend")
@Data
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {
    private String homePageUrl;
    private String authUrl;
    @Resource
    private UserService userService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
            Map<String, Object> attributes = oauthToken.getPrincipal().getAttributes();

            // 从 GitHub 返回的信息中提取用户数据
            // GitHub 用户 ID
            Long githubId = Long.valueOf(attributes.get("id").toString());
            // GitHub 用户名
            String login = (String) attributes.get("login");
            // GitHub 用户头像
            String avatarUrl = (String) attributes.get("avatar_url");
            // GitHub 用户昵称
            String name = (String) attributes.get("name");
            if (StrUtil.isBlank(name)) {
                name = login;
            }

            // 查找是否已存在该 GitHub 用户
            User user = userService.queryChain().eq(User::getGithubId, githubId).one();

            if (user == null) {
                // 首次 GitHub 登录，自动创建用户
                user = User.builder()
                        .githubId(githubId)
                        .userAccount("github_" + login)
                        .userPassword("")
                        .userName(name)
                        .userAvatar(avatarUrl)
                        .userRole("user")
                        .vipLevel("0")
                        .quota(5)
                        .build();
                userService.save(user);
                log.info("GitHub 用户首次登录，已创建账号: {}", login);
            } else {
                // 已有用户，更新头像和昵称
                user.setUserName(name);
                user.setUserAvatar(avatarUrl);
                userService.updateById(user);
                log.info("GitHub 用户登录: {}", login);
            }

            // 设置 Session（和账号密码登录保持一致）
            request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, user);

            // 重定向到前端首页
            response.sendRedirect(homePageUrl);

        } catch (Exception e) {
            log.error("GitHub OAuth 登录失败", e);
            response.sendRedirect(authUrl);
        }
    }
}
