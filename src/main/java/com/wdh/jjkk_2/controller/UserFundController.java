package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.dto.AuthSession;
import com.wdh.jjkk_2.dto.UserDtos;
import com.wdh.jjkk_2.service.UserAvatarStorageService;
import com.wdh.jjkk_2.service.UserFundService;

import com.wdh.jjkk_2.service.AuthService;
import com.wdh.jjkk_2.common.ApiResponse;
import com.wdh.jjkk_2.common.BusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 用户资料、自选基金和旧版持有接口。
 *
 * 用户维度接口都会把 Authorization 中的 Bearer token 与路径里的 userId 做校验，
 * 只有 token 对应的本人才能读取或修改自己的自选、头像、昵称等数据。这一层校验
 * 是多用户测试时最重要的隔离边界，防止 A 用户通过改 URL 访问 B 用户数据。
 */
@RestController
@RequestMapping("/users")
public class UserFundController {
    private final UserFundService userFundService;
    private final AuthService authService;
    private final UserAvatarStorageService userAvatarStorageService;

    public UserFundController(
            UserFundService userFundService,
            AuthService authService,
            UserAvatarStorageService userAvatarStorageService
    ) {
        this.userFundService = userFundService;
        this.authService = authService;
        this.userAvatarStorageService = userAvatarStorageService;
    }

    @PostMapping
    public ApiResponse<UserDtos.UserResponse> createOrUpdateUser(@Valid @RequestBody UserDtos.CreateUserRequest request) {
            throw new BusinessException(HttpStatus.GONE, "\u7528\u6237\u5df2\u5220\u9664");
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserDtos.UserResponse> getUser(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(userFundService.getUser(userId));
    }

    @PutMapping("/{userId}/profile")
    public ApiResponse<UserDtos.UserResponse> updateProfile(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody UserDtos.UserProfileUpdateRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(userFundService.updateProfile(userId, request));
    }

    @PostMapping(value = "/{userId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserDtos.UserResponse> uploadAvatar(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestPart("file") MultipartFile file
    ) {
        authService.requireUser(authorization, userId);
        String avatarUrl = userAvatarStorageService.store(userId, file);
        return ApiResponse.ok(userFundService.updateAvatar(userId, avatarUrl));
    }

    /**
     * 查询当前登录用户的自选场外基金列表。
     *
     * 返回结果已经关联最新官方净值和估算快照，前端自选页可以直接展示基金名称、
     * 官方日涨幅、估算涨幅、更新时间和数据状态，不需要再逐个基金额外请求详情。
     */
    @GetMapping("/{userId}/watchlist")
    public ApiResponse<List<UserDtos.WatchlistItemResponse>> listWatchlist(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(userFundService.listWatchlist(userId));
    }

    /**
     * 添加或更新一只自选基金。
     *
     * 写入自选关系后，会尽力立刻刷新一次该基金估算，让用户刚添加基金时尽快看到
     * 最新数据；如果公开源短暂不可用，也不会阻塞添加，下一轮分钟任务会继续补齐。
     */
    @PostMapping("/{userId}/watchlist")
    public ApiResponse<UserDtos.WatchlistItemResponse> addWatchlist(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody UserDtos.WatchlistRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok("\u5df2\u52a0\u5165\u81ea\u9009", userFundService.addWatchlist(userId, request));
    }

    /**
     * 保存小程序拖拽或手动调整后的自选排序。
     *
     * 排序值只作用于当前用户，不影响其他测试用户；前端刷新自选页时按该顺序恢复。
     */
    @PutMapping("/{userId}/watchlist/order")
    public ApiResponse<List<UserDtos.WatchlistItemResponse>> reorderWatchlist(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody UserDtos.WatchlistOrderRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok("\u81ea\u9009\u6392\u5e8f\u5df2\u4fdd\u5b58", userFundService.reorderWatchlist(userId, request));
    }

    @PutMapping("/{userId}/watchlist/{watchId}")
    public ApiResponse<UserDtos.WatchlistItemResponse> updateWatchlist(
            @PathVariable Long userId,
            @PathVariable Long watchId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody UserDtos.WatchlistUpdateRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok("\u81ea\u9009\u5df2\u66f4\u65b0", userFundService.updateWatchlist(userId, watchId, request));
    }

    @DeleteMapping("/{userId}/watchlist/{watchId}")
    public ApiResponse<Void> deleteWatchlist(
            @PathVariable Long userId,
            @PathVariable Long watchId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        authService.requireUser(authorization, userId);
        userFundService.deleteWatchlist(userId, watchId);
        return ApiResponse.ok();
    }

    @GetMapping("/{userId}/holdings")
    public ApiResponse<List<UserDtos.HoldingResponse>> listHoldings(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String fundCode,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(userFundService.listHoldings(userId, fundCode, accountId, sortBy, sortDirection));
    }

    /**
     * 持有看板聚合接口（推荐前端优先调用）：
     * 一次返回 summary/accounts/holdings/transactions，减少移动端并发请求数量。
     */
    @GetMapping("/{userId}/holdings/dashboard")
    public ApiResponse<UserDtos.HoldingDashboardResponse> holdingDashboard(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String fundCode,
            @RequestParam(required = false) Integer txnLimit,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(userFundService.holdingDashboard(
                userId,
                accountId,
                fundCode,
                txnLimit,
                sortBy,
                sortDirection
        ));
    }

    @GetMapping("/{userId}/holdings/summary")
    public ApiResponse<UserDtos.HoldingSummaryResponse> holdingSummary(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(userFundService.holdingSummary(userId));
    }

    @GetMapping("/{userId}/holdings/transactions")
    public ApiResponse<List<UserDtos.HoldingTransactionResponse>> listHoldingTransactions(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String fundCode,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) Integer limit
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(userFundService.listHoldingTransactions(userId, fundCode, accountId, limit));
    }

    @PostMapping("/{userId}/holdings/trades")
    public ApiResponse<UserDtos.HoldingTradeResponse> simulateHoldingTrade(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody UserDtos.HoldingTradeRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok("\u6a21\u62df\u6210\u4ea4\u5df2\u8bb0\u5f55", userFundService.simulateTrade(userId, request));
    }

    @PostMapping("/{userId}/holdings")
    public ApiResponse<UserDtos.HoldingResponse> addHolding(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody UserDtos.HoldingCreateRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok("\u6301\u6709\u5df2\u6dfb\u52a0", userFundService.addHolding(userId, request));
    }

    @PutMapping("/{userId}/holdings/{holdingId}")
    public ApiResponse<UserDtos.HoldingResponse> updateHolding(
            @PathVariable Long userId,
            @PathVariable Long holdingId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody UserDtos.HoldingUpdateRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok("\u6301\u6709\u5df2\u66f4\u65b0", userFundService.updateHolding(userId, holdingId, request));
    }

    @DeleteMapping("/{userId}/holdings/{holdingId}")
    public ApiResponse<Void> deleteHolding(
            @PathVariable Long userId,
            @PathVariable Long holdingId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        authService.requireUser(authorization, userId);
        userFundService.deleteHolding(userId, holdingId);
        return ApiResponse.ok();
    }
}
