package com.wdh.jjkk_2.mapper;

import com.wdh.jjkk_2.dto.UserDtos;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 用户、自选和旧版持有查询结果到 DTO 的映射工具。
 *
 * 用户模块的 SQL 经常会关联用户表、基金表、净值快照和估算快照。把字段转换集中在
 * 这里，可以让 UserFundService 只关心业务流程。旧版持有映射还会根据最新估算净值
 * 计算估算市值和盈亏，保证这些展示口径在所有接口中保持一致。
 */
public final class UserFundRowMapper {
    private UserFundRowMapper() {
    }

    /**
     * 映射 app_user 用户行，并计算对外展示的 9 位用户编号和注册天数。
     *
     * 数据库主键仍然使用自增 id，前端展示时统一加上 100000000，既满足“9 位递增数字”
     * 的展示要求，也不会改变数据库关联关系。
     */
    public static RowMapper<UserDtos.UserResponse> user() {
        return (rs, rowNum) -> {
            LocalDateTime createdAt = localDateTime(rs, "created_at");
            long registeredDays = createdAt == null
                    ? 1
                    : Math.max(1, ChronoUnit.DAYS.between(createdAt.toLocalDate(), LocalDate.now()) + 1);
            return new UserDtos.UserResponse(
                    rs.getLong("id"),
                    displayUserId(rs.getLong("id")),
                    rs.getString("wechat_openid"),
                    rs.getString("wechat_unionid"),
                    rs.getString("nickname"),
                    rs.getString("avatar_url"),
                    rs.getString("status"),
                    localDateTime(rs, "last_login_at"),
                    createdAt,
                    registeredDays
            );
        };
    }

    /**
     * 映射一条自选基金记录。
     *
     * SQL 已经把基金基础信息、官方净值和估算快照关联好，这里只负责按前端字段顺序
     * 组装响应，供自选页直接渲染。
     */
    public static RowMapper<UserDtos.WatchlistItemResponse> watchlist() {
        return (rs, rowNum) -> new UserDtos.WatchlistItemResponse(
                rs.getLong("id"),
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("fund_type"),
                rs.getString("group_name"),
                rs.getInt("display_order"),
                rs.getString("remark"),
                rs.getBoolean("alert_enabled"),
                localDate(rs, "nav_date"),
                rs.getBigDecimal("unit_nav"),
                rs.getBigDecimal("daily_return_pct"),
                localDateTime(rs, "quote_time"),
                rs.getBigDecimal("estimate_nav"),
                rs.getBigDecimal("estimate_return_pct"),
                rs.getString("data_status")
        );
    }

    /**
     * 映射一条旧版持有记录，并计算估算市值和估算盈亏。
     *
     * 虽然当前小程序先隐藏持有功能，但保留后端兼容接口。计算时使用最新 estimate_nav，
     * 如果持有份额、成本金额或估算净值缺失，就返回 null，避免给出误导性盈亏。
     */
    public static RowMapper<UserDtos.HoldingResponse> holding() {
        return (rs, rowNum) -> {
            BigDecimal holdingShare = rs.getBigDecimal("holding_share");
            BigDecimal costAmount = rs.getBigDecimal("cost_amount");
            BigDecimal estimateNav = rs.getBigDecimal("estimate_nav");
            BigDecimal estimateMarketValue = multiplyNullable(holdingShare, estimateNav);
            BigDecimal estimateProfitLossAmount = subtractNullable(estimateMarketValue, costAmount);
            BigDecimal estimateProfitLossPct = ratioPct(estimateProfitLossAmount, costAmount);

            return new UserDtos.HoldingResponse(
                    rs.getLong("id"),
                    rs.getLong("account_id"),
                    rs.getString("account_name"),
                    rs.getString("platform_name"),
                    rs.getString("fund_code"),
                    rs.getString("fund_name"),
                    rs.getString("fund_type"),
                    holdingShare,
                    rs.getBigDecimal("frozen_share"),
                    costAmount,
                    rs.getBigDecimal("avg_cost_nav"),
                    rs.getBigDecimal("confirmed_amount"),
                    rs.getBigDecimal("profit_loss_amount"),
                    rs.getBigDecimal("profit_loss_pct"),
                    localDate(rs, "first_buy_date"),
                    localDate(rs, "nav_date"),
                    rs.getBigDecimal("unit_nav"),
                    localDateTime(rs, "quote_time"),
                    estimateNav,
                    estimateMarketValue,
                    estimateProfitLossAmount,
                    estimateProfitLossPct,
                    rs.getString("data_status"),
                    rs.getString("status")
            );
        };
    }

    /**
     * 映射模拟交易流水。
     * 用于持有板块“交易记录”列表，展示买卖方向、成交净值、金额、份额与时间。
     */
    public static RowMapper<UserDtos.HoldingTransactionResponse> holdingTransaction() {
        return (rs, rowNum) -> new UserDtos.HoldingTransactionResponse(
                rs.getLong("id"),
                rs.getObject("holding_id", Long.class),
                rs.getObject("account_id", Long.class),
                rs.getString("account_name"),
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("txn_type"),
                localDate(rs, "txn_date"),
                localDate(rs, "confirm_date"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("share"),
                rs.getBigDecimal("nav"),
                rs.getBigDecimal("fee"),
                rs.getString("source_type"),
                rs.getString("remark"),
                localDateTime(rs, "created_at")
        );
    }

    private static String displayUserId(Long id) {
        long safeId = id == null ? 0 : id;
        return String.valueOf(100_000_000L + safeId);
    }

    private static BigDecimal multiplyNullable(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.multiply(right).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal subtractNullable(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.subtract(right).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratioPct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || BigDecimal.ZERO.compareTo(denominator) == 0) {
            return null;
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
