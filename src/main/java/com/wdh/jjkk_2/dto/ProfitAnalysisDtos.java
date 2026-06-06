package com.wdh.jjkk_2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ProfitAnalysisDtos {
    private ProfitAnalysisDtos() {
    }

    public record AccountResponse(
            Long accountId,
            String accountName,
            String platformName,
            Integer holdingCount
    ) {
    }

    public record SummaryResponse(
            LocalDate date,
            BigDecimal profitAmount,
            BigDecimal returnPct,
            BigDecimal previousMarketValue,
            BigDecimal currentMarketValue,
            LocalDateTime updatedAt,
            String dataStatus
    ) {
    }

    public record PointResponse(
            String key,
            LocalDate date,
            LocalDateTime quoteTime,
            BigDecimal profitAmount,
            BigDecimal returnPct
    ) {
    }

    public record CalendarItemResponse(
            String key,
            String label,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal profitAmount,
            BigDecimal returnPct,
            Boolean hasData
    ) {
    }

    public record DetailResponse(
            String fundCode,
            String fundName,
            String accountName,
            BigDecimal profitAmount,
            BigDecimal returnPct,
            BigDecimal weightPct,
            BigDecimal holdingShare
    ) {
    }

    public record AnalysisResponse(
            Long selectedAccountId,
            List<AccountResponse> accounts,
            SummaryResponse summary,
            String trendRange,
            List<PointResponse> intraday,
            List<PointResponse> trend,
            String calendarMode,
            LocalDate calendarAnchor,
            List<CalendarItemResponse> calendar,
            String selectedKey,
            List<DetailResponse> details,
            LocalDateTime serverTime
    ) {
    }
}
