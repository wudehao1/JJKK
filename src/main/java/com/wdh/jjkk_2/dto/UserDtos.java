package com.wdh.jjkk_2.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户资料、自选基金和旧版持有功能的数据传输对象。
 *
 * 用户接口返回数据库 id 和 9 位展示 id；自选接口返回基金展示所需的最新净值、估算涨幅和
 * 数据状态；持有相关对象暂时保留给旧功能兼容，后续如果重新开放持有页可以继续复用。
 */
public final class UserDtos {
    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 128) String wechatOpenid,
            @Size(max = 128) String wechatUnionid,
            @Size(max = 128) String nickname,
            @Size(max = 512) String avatarUrl
    ) {
    }

    public record UserResponse(
            Long id,
            String displayUserId,
            String wechatOpenid,
            String wechatUnionid,
            String nickname,
            String avatarUrl,
            String status,
            LocalDateTime lastLoginAt,
            LocalDateTime createdAt,
            Long registeredDays
    ) {
    }

    public record UserProfileUpdateRequest(
            @Size(max = 128) String nickname,
            @Size(max = 512) String avatarUrl
    ) {
    }

    public record WatchlistRequest(
            @NotBlank @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode,
            @Size(max = 64) String groupName,
            Integer displayOrder,
            @Size(max = 255) String remark,
            Boolean alertEnabled
    ) {
    }

    public record WatchlistUpdateRequest(
            @Size(max = 64) String groupName,
            Integer displayOrder,
            @Size(max = 255) String remark,
            Boolean alertEnabled
    ) {
    }

    public record WatchlistOrderRequest(
            @NotNull @Size(max = 100) List<WatchlistOrderItem> items
    ) {
    }

    public record WatchlistOrderItem(
            @NotNull Long watchId,
            @NotNull Integer displayOrder
    ) {
    }

    public record WatchlistItemResponse(
            Long id,
            String fundCode,
            String fundName,
            String fundType,
            String groupName,
            Integer displayOrder,
            String remark,
            Boolean alertEnabled,
            LocalDate latestNavDate,
            BigDecimal latestUnitNav,
            BigDecimal latestDailyReturnPct,
            LocalDateTime estimateTime,
            BigDecimal estimateNav,
            BigDecimal estimateReturnPct,
            String dataStatus
    ) {
    }

    public record HoldingCreateRequest(
            @NotBlank @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode,
            @Size(max = 64) String accountName,
            @Size(max = 64) String platformName,
            @NotNull @DecimalMin(value = "0.0000", inclusive = true) BigDecimal holdingShare,
            BigDecimal frozenShare,
            @NotNull @DecimalMin(value = "0.0000", inclusive = true) BigDecimal costAmount,
            BigDecimal avgCostNav,
            LocalDate firstBuyDate
    ) {
    }

    public record HoldingUpdateRequest(
            BigDecimal holdingShare,
            BigDecimal frozenShare,
            BigDecimal costAmount,
            BigDecimal avgCostNav,
            LocalDate firstBuyDate,
            @Size(max = 16) String status
    ) {
    }

    public record HoldingResponse(
            Long id,
            Long accountId,
            String accountName,
            String platformName,
            String fundCode,
            String fundName,
            String fundType,
            BigDecimal holdingShare,
            BigDecimal frozenShare,
            BigDecimal costAmount,
            BigDecimal avgCostNav,
            BigDecimal confirmedAmount,
            BigDecimal profitLossAmount,
            BigDecimal profitLossPct,
            LocalDate firstBuyDate,
            LocalDate latestNavDate,
            BigDecimal latestUnitNav,
            LocalDateTime estimateTime,
            BigDecimal estimateNav,
            BigDecimal estimateMarketValue,
            BigDecimal estimateProfitLossAmount,
            BigDecimal estimateProfitLossPct,
            String dataStatus,
            String status
    ) {
    }

    /**
     * 持有板块首页汇总数据。
     * totalCostAmount: 当前持仓成本合计；
     * totalMarketValue: 按估算净值计算的持仓市值合计；
     * totalProfitLossAmount / totalProfitLossPct: 估算口径总盈亏；
     * holdingCount / activeHoldingCount: 持仓条目总数与有效条目数。
     */
    public record HoldingSummaryResponse(
            BigDecimal totalCostAmount,
            BigDecimal totalMarketValue,
            BigDecimal totalProfitLossAmount,
            BigDecimal totalProfitLossPct,
            Integer holdingCount,
            Integer activeHoldingCount,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * 持有账户聚合信息。
     * 用于前端“持有”页的账户筛选与账户级统计展示。
     */
    public record HoldingAccountSummaryResponse(
            Long accountId,
            String accountName,
            String platformName,
            Integer holdingCount,
            BigDecimal totalCostAmount,
            BigDecimal totalMarketValue,
            BigDecimal totalProfitLossAmount,
            BigDecimal totalProfitLossPct
    ) {
    }

    /**
     * 持有页聚合响应：
     * 1) 总览 summary
     * 2) 账户维度 accounts
     * 3) 持有明细 holdings
     * 4) 最近交易 transactions
     * 5) 服务端时间 serverTime（便于前端显示刷新时点）
     */
    public record HoldingDashboardResponse(
            HoldingSummaryResponse summary,
            List<HoldingAccountSummaryResponse> accounts,
            List<HoldingResponse> holdings,
            List<HoldingTransactionResponse> transactions,
            LocalDateTime serverTime
    ) {
    }

    /**
     * 模拟交易请求。
     * BUY/SELL 交易会自动回写 user_fund_holding，并记录到 user_holding_txn。
     * nav、amount、share 至少需要提供两项中的一项用于推导，fee 可选，默认 0。
     */
    public record HoldingTradeRequest(
            @NotBlank @Pattern(regexp = "\\d{6}", message = "基金代码必须是6位数字") String fundCode,
            @NotBlank @Pattern(regexp = "BUY|SELL", message = "txnType 仅支持 BUY 或 SELL") String txnType,
            Long accountId,
            @Size(max = 64) String accountName,
            @Size(max = 64) String platformName,
            LocalDate txnDate,
            LocalDate confirmDate,
            @Pattern(regexp = "AUTO|TODAY|YESTERDAY|TOMORROW", message = "navOption only supports AUTO/TODAY/YESTERDAY/TOMORROW") String navOption,
            @DecimalMin(value = "0.000001", inclusive = true) BigDecimal nav,
            @DecimalMin(value = "0.0000", inclusive = true) BigDecimal amount,
            @DecimalMin(value = "0.0000", inclusive = true) BigDecimal share,
            @DecimalMin(value = "0.0000", inclusive = true) BigDecimal fee,
            @Size(max = 255) String remark
    ) {
    }

    /**
     * 模拟交易后返回的数据。
     * transaction 为本次成交流水；holding 为成交后的最新持仓；
     * realizedProfitLossAmount / realizedProfitLossPct 仅 SELL 时有值。
     */
    public record HoldingTradeResponse(
            HoldingTransactionResponse transaction,
            HoldingResponse holding,
            BigDecimal realizedProfitLossAmount,
            BigDecimal realizedProfitLossPct,
            String tradeStatus,
            String tradeStatusText,
            Long pendingTradeId,
            LocalDate targetNavDate
    ) {
    }

    /**
     * 持仓成交流水明细。
     */
    public record HoldingTransactionResponse(
            Long id,
            Long holdingId,
            Long accountId,
            String accountName,
            String fundCode,
            String fundName,
            String txnType,
            LocalDate txnDate,
            LocalDate confirmDate,
            BigDecimal amount,
            BigDecimal share,
            BigDecimal nav,
            BigDecimal fee,
            String sourceType,
            String remark,
            LocalDateTime createdAt
    ) {
    }
}
