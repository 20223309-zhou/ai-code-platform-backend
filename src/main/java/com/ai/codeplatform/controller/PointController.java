package com.ai.codeplatform.controller;

import com.ai.codeplatform.annotation.AuthCheck;
import com.ai.codeplatform.common.BaseResponse;
import com.ai.codeplatform.common.ResultUtils;
import com.ai.codeplatform.constant.UserConstant;
import com.ai.codeplatform.model.dto.point.PointExchangeRequest;
import com.ai.codeplatform.model.entity.PointRecord;
import com.ai.codeplatform.model.entity.User;
import com.ai.codeplatform.model.vo.ExchangeResultVO;
import com.ai.codeplatform.model.vo.PointAccountVO;
import com.ai.codeplatform.model.vo.PointGoodsVO;
import com.ai.codeplatform.model.vo.SignInResultVO;
import com.ai.codeplatform.service.PointService;
import com.ai.codeplatform.service.UserService;
import com.mybatisflex.core.paginate.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 签到与积分控制器。
 */
@RestController
@RequestMapping("/point")
@Slf4j
@Tag(name = "PointController", description = "签到与积分接口")
public class PointController {

    @Resource
    private PointService pointService;

    @Resource
    private UserService userService;

    @GetMapping("/account")
    @Operation(summary = "获取积分账户概览")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<PointAccountVO> getAccount(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pointService.getAccount(loginUser.getId()));
    }

    @PostMapping("/sign-in")
    @Operation(summary = "每日签到")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<SignInResultVO> signIn(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pointService.signIn(loginUser.getId()));
    }

    @GetMapping("/goods")
    @Operation(summary = "积分商城商品列表")
    public BaseResponse<List<PointGoodsVO>> listGoods() {
        return ResultUtils.success(pointService.listGoods());
    }

    @PostMapping("/exchange")
    @Operation(summary = "积分兑换生成额度")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<ExchangeResultVO> exchange(@RequestBody PointExchangeRequest exchangeRequest,
                                                   HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        int count = exchangeRequest.getCount() == null ? 1 : exchangeRequest.getCount();
        return ResultUtils.success(
                pointService.exchange(loginUser.getId(), exchangeRequest.getGoodsId(), count));
    }

    @GetMapping("/record")
    @Operation(summary = "积分流水分页")
    @AuthCheck(mustRole = UserConstant.DEFAULT_ROLE)
    public BaseResponse<Page<PointRecord>> pageRecords(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pointService.pageRecords(loginUser.getId(), pageNum, pageSize));
    }
}
