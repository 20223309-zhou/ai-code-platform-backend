package com.ai.codeplatform.service;

import com.ai.codeplatform.model.dto.app.AppQueryRequest;
import com.ai.codeplatform.model.vo.AppVO;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.ai.codeplatform.model.entity.App;

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

    List<AppVO> getAppVOList(List<App> appList);
}
