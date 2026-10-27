package com.ai.codeplatform.model.dto.app;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 方案预选请求。
 * <p>
 * 这个接口发生在创建应用之前，所以不需要 appId —— 方案与具体的应用实例无关，是纯 stateless 的。
 * 换方案场景（应用已存在）复用同一个接口，靠 {@link #excludeTitles} 让模型避开已有方向。
 */
@Data
public class AppPlanRequest implements Serializable {

    /**
     * 用户的应用描述
     */
    private String initPrompt;

    /**
     * 已经看过或已经采用过的方案标题。
     * <p>
     * 只在「换一个方案」时传：不传的话模型很可能又给出和上次一样的两个方向，
     * 用户点了半天发现还是同一套布局。
     */
    private List<String> excludeTitles;

    private static final long serialVersionUID = 1L;
}
