package com.wdh.jjkk_2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 大盘和市场首页相关的数据传输对象。
 *
 * 首页概览、市场广度、排行榜、当日分时和历史日线都在这里定义响应结构。不同市场的数据源
 * 可以不同，但返回给小程序的字段保持一致，前端图表组件才能复用。
 */
public final class MarketDtos {
    private MarketDtos() {
    }

    public record OverviewResponse(
            LocalDate tradingDay,
            LocalDateTime updatedAt,
            String dataStatus,
            List<IndexQuoteResponse> indices
    ) {
    }

    public record IndexQuoteResponse(
            String market,
            String symbol,
            String name,
            BigDecimal lastPrice,
            BigDecimal changeAmount,
            BigDecimal changePct,
            BigDecimal turnover,
            Integer dataLagSeconds,
            LocalDateTime quoteTime
    ) {
    }

    public record FundBreadthResponse(
            LocalDate tradingDay,
            LocalDateTime updatedAt,
            Integer upCount,
            Integer downCount,
            Integer flatCount,
            Integer totalCount,
            BigDecimal upRatioPct,
            BigDecimal downRatioPct
    ) {
    }

    public record FundRankingResponse(
            String fundCode,
            String fundName,
            String sectorName,
            LocalDate latestNavDate,
            BigDecimal latestUnitNav,
            BigDecimal returnPct,
            String dataType
    ) {
    }

    public record SectorRankingResponse(
            String code,
            String name,
            BigDecimal latestValue,
            BigDecimal changeAmount,
            BigDecimal changePct,
            BigDecimal mainNetInflow,
            String direction
    ) {
    }

    public record MinuteSeriesResponse(
            String market,
            String symbol,
            String name,
            LocalDate tradingDay,
            LocalDateTime updatedAt,
            String dataStatus,
            List<MinutePointResponse> points
    ) {
    }

    public record MinutePointResponse(
            LocalDateTime quoteTime,
            BigDecimal price,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover
    ) {
    }

    public record HistorySeriesResponse(
            String market,
            String symbol,
            String name,
            String range,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime updatedAt,
            String dataStatus,
            List<HistoryPointResponse> points
    ) {
    }

    public record HistoryPointResponse(
            LocalDate tradingDay,
            BigDecimal openPrice,
            BigDecimal closePrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover
    ) {
    }
}

