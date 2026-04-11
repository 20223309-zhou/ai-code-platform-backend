package com.ai.codeplatform.service;

import com.ai.codeplatform.model.dto.app.AppQueryRequest;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.model.vo.AppVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.ai.codeplatform.model.entity.App;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author Administrator
 */
public interface AppService extends IService<App> {
    /**
     * 获取应用视图对象
     * @param app 应用
     * @return 应用视图对象
     */
    AppVO getAppVO(App app);

    /**
     * 获取查询条件
     * @param appQueryRequest 查询条件
     * @return 查询条件
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 获取应用视图对象列表
     * @param appList 应用列表
     * @return 应用视图对象列表
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 聊天生成代码
     * @param appId 应用id
     * @param message 消息
     * @param loginUser 登录用户
     * @return 代码
     */
    Flux<String> chatToGenCode(Long appId, String message, User loginUser);

    /**
     * 部署应用
     * @param appId 应用id
     * @param loginUser 登录用户
     * @return 部署结果
     */
    String deployApp(Long appId, User loginUser);
}
