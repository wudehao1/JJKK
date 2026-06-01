package com.wdh.jjkk_2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 基金模块的请求和响应对象集合。
 *
 * 这里把基金基础信息、详情、自选列表摘要、当日分时估算点、历史净值点等接口契约集中
 * 放在一起，方便前端按页面使用：自选页用 SummaryResponse，详情页用 DetailResponse，
 * 当日图用 MinuteSeriesResponse，长期走势用 HistorySeriesResponse。
 */
public final class FundDtos {
    private FundDtos() {
    }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode,
            @NotBlank @Size(max = 200) String fundName,
            @Size(max = 128) String fundShortName,
            @Size(max = 16) String shareClassCode,
            @Size(max = 32) String fundType,
            @Size(max = 32) String operationMode,
            @Size(max = 32) String riskLevel,
            @Size(max = 3) String currency,
            LocalDate inceptionDate,
            @Size(max = 1000) String benchmark,
            @Size(max = 32) String trackingIndexCode,
            Boolean indexFund,
            Boolean qdii,
            Boolean fof,
            Boolean reit,
            @Size(max = 128) String companyName,
            @Size(max = 64) String companyShortName,
            @Size(max = 128) String custodianName,
            @Size(max = 64) String custodianShortName
    ) {
    }

    public record UpdateRequest(
            @Size(max = 200) String fundName,
            @Size(max = 128) String fundShortName,
            @Size(max = 16) String shareClassCode,
            @Size(max = 32) String fundType,
            @Size(max = 32) String operationMode,
            @Size(max = 32) String riskLevel,
            @Size(max = 3) String currency,
            LocalDate inceptionDate,
            @Size(max = 1000) String benchmark,
            @Size(max = 32) String trackingIndexCode,
            Boolean indexFund,
            Boolean qdii,
            Boolean fof,
            Boolean reit,
            @Size(max = 128) String companyName,
            @Size(max = 64) String companyShortName,
            @Size(max = 128) String custodianName,
            @Size(max = 64) String custodianShortName,
            @Size(max = 32) String purchaseStatus,
            @Size(max = 32) String redeemStatus
    ) {
    }

    public record SummaryResponse(
            String fundCode,
            String fundName,
            String fundAbbr,
            String shareClassCode,
            String fundType,
            String operationMode,
            String riskLevel,
            Boolean indexFund,
            String trackingIndexCode,
            String companyName,
            LocalDate latestNavDate,
            BigDecimal latestUnitNav,
            BigDecimal latestDailyReturnPct,
            LocalDateTime estimateTime,
            BigDecimal estimateNav,
            BigDecimal estimateReturnPct,
            String dataStatus,
            String status
    ) {
    }

    public record DetailResponse(
            Long productId,
            Long shareClassId,
            String fundCode,
            String fundName,
            String fundAbbr,
            String shareClassCode,
            String fundType,
            String operationMode,
            String riskLevel,
            String currency,
            LocalDate inceptionDate,
            String benchmark,
            String trackingIndexCode,
            Boolean indexFund,
            Boolean qdii,
            Boolean fof,
            Boolean reit,
            String companyName,
            String custodianName,
            String purchaseStatus,
            String redeemStatus,
            LocalDate latestNavDate,
            BigDecimal latestUnitNav,
            BigDecimal latestDailyReturnPct,
            LocalDateTime estimateTime,
            BigDecimal estimateNav,
            BigDecimal estimateReturnPct,
            BigDecimal confidenceScore,
            String dataStatus,
            String status
    ) {
    }

    public record MinuteSeriesResponse(
            String fundCode,
            String fundName,
            LocalDate tradingDay,
            LocalDateTime updatedAt,
            String dataStatus,
            String dataType,
            List<EstimateMinutePointResponse> points
    ) {
    }

    public record EstimateMinutePointResponse(
            LocalDateTime quoteTime,
            BigDecimal estimateNav,
            BigDecimal estimateReturnPct,
            BigDecimal estimateChangeAmount,
            BigDecimal officialLastNav,
            LocalDate officialNavDate,
            BigDecimal confidenceScore,
            Integer dataLagSeconds
    ) {
    }

    public record HistorySeriesResponse(
            String fundCode,
            String fundName,
            String range,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime updatedAt,
            String dataStatus,
            List<HistoryPointResponse> points
    ) {
    }

    public record HistoryPointResponse(
            LocalDate navDate,
            BigDecimal unitNav,
            BigDecimal accumulatedNav,
            BigDecimal dailyReturnPct
    ) {
    }
}
