package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.dto.FundDtos;

import com.wdh.jjkk_2.common.BusinessException;
import com.wdh.jjkk_2.common.PageResponse;
import com.wdh.jjkk_2.mapper.FundRowMapper;
import com.wdh.jjkk_2.service.IntradayRedisCacheService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小程序基金查询门面服务。
 *
 * 这里统一编排基金基础信息、官方净值同步、当日估算刷新、Redis 分钟缓存读取和
 * MySQL 历史兜底读取。Controller 只负责暴露接口，Mapper 只负责字段映射，
 * 具体“从哪里拿数据、拿不到怎么兜底、是否需要先刷新”都集中在这个服务里。
 */
@Service
public class FundService {
    private static final Set<String> FUND_TYPES = Set.of(
            "STOCK", "MIXED", "BOND", "INDEX", "QDII", "FOF", "MONEY_MARKET", "COMMODITY", "REIT", "OTHER"
    );
    private static final Set<String> OPERATION_MODES = Set.of("OPEN", "CLOSED", "REGULAR_OPEN", "ETF_LINK", "LOF", "OTHER");
    private static final Set<String> TRADE_STATUS = Set.of("OPEN", "SUSPENDED", "CLOSED", "LIMITED", "UNKNOWN");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final FundQuoteRefreshService fundQuoteRefreshService;
    private final IntradayRedisCacheService intradayRedisCacheService;
    private final FundNavCacheService fundNavCacheService;

    public FundService(
            NamedParameterJdbcTemplate jdbcTemplate,
            FundQuoteRefreshService fundQuoteRefreshService,
            IntradayRedisCacheService intradayRedisCacheService,
            FundNavCacheService fundNavCacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.fundQuoteRefreshService = fundQuoteRefreshService;
        this.intradayRedisCacheService = intradayRedisCacheService;
        this.fundNavCacheService = fundNavCacheService;
    }

    /**
     * 搜索本地基金基础信息。
     *
     * 官方搜索和导入由 {@link OfficialFundImportService} 负责，本方法只读取已经写入
     * MySQL 的标准化基金记录。这样可以把“拉取公开源”和“查询本地库”分开，便于缓存、
     * 分页和权限控制。
     */
    public PageResponse<FundDtos.SummaryResponse> search(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String normalizedKeyword = StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("keyword", normalizedKeyword)
                .addValue("limit", safeSize)
                .addValue("offset", (safePage - 1) * safeSize);

        String where = """
                WHERE (:keyword IS NULL
                   OR fsc.fund_code LIKE :keyword
                   OR fsc.fund_name LIKE :keyword
                   OR fsc.fund_abbr LIKE :keyword
                   OR fsc.pinyin_abbr LIKE :keyword)
                """;

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM fund_share_class fsc
                JOIN fund_product fp ON fp.id = fsc.product_id
                """ + where, params, Long.class);

        List<FundDtos.SummaryResponse> items = jdbcTemplate.query("""
                SELECT
                  fsc.fund_code,
                  fsc.fund_name,
                  fsc.fund_abbr,
                  fsc.share_class_code,
                  fsc.status,
                  fp.fund_type,
                  fp.operation_mode,
                  fp.risk_level,
                  fp.is_index_fund,
                  fp.tracking_index_code,
                  COALESCE(fc.company_short_name, fc.company_name) AS company_name,
                  nav.nav_date,
                  nav.unit_nav,
                  nav.daily_return_pct,
                  snap.quote_time,
                  snap.estimate_nav,
                  snap.estimate_return_pct,
                  snap.data_status
                FROM fund_share_class fsc
                JOIN fund_product fp ON fp.id = fsc.product_id
                LEFT JOIN fund_company fc ON fc.id = fp.company_id
                LEFT JOIN (
                  SELECT n1.fund_code, n1.nav_date, n1.unit_nav, n1.daily_return_pct
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = fsc.fund_code
                LEFT JOIN fund_realtime_snapshot snap ON snap.fund_code = fsc.fund_code
                """ + where + """
                ORDER BY fsc.fund_code
                LIMIT :limit OFFSET :offset
                """, params, FundRowMapper.summary());

        return new PageResponse<>(items, safePage, safeSize, total == null ? 0 : total);
    }

    /**
     * 返回单只基金详情，并先同步最近一段官方净值。
     *
     * 用户点进基金详情页时通常马上会打开当日分时和历史走势，因此这里会把详情短暂缓存到
     * Redis。缓存可以减少同一用户反复进入详情页时的数据库压力，同时不影响估算接口继续
     * 按分钟刷新。
     */
    public FundDtos.DetailResponse getByCode(String fundCode) {
        LocalDate endDate = LocalDate.now();
        fundQuoteRefreshService.syncFundNavHistory(fundCode, endDate.minusDays(10), endDate);
        fundNavCacheService.loadLatest(fundCode);
        fundQuoteRefreshService.reconcileClosedTradingDay(fundCode);
        FundDtos.DetailResponse detail = findByCode(fundCode);
        intradayRedisCacheService.cacheFundDetail(detail);
        return detail;
    }

    /**
     * 返回基金当日分时估算点，并尽量保证图表不空白。
     *
     * 读取顺序如下：
     * 1. 先尝试刷新当前最新一分钟估算；
     * 2. 如果官方收盘净值已披露，先把最后一个估算点修正为真实涨幅；
     * 3. 优先读取 Redis 中当日分钟 hash，保证图表读取速度；
     * 4. Redis 没有时读取 MySQL 已持久化的分钟表；
     * 5. 仍然没有时用最新估算快照兜底，让前端至少能画出一个点并展示状态。
     */
    public FundDtos.MinuteSeriesResponse minuteSeries(String fundCode) {
        FundDtos.DetailResponse fund = findByCode(fundCode);
        intradayRedisCacheService.cacheFundDetail(fund);
        fundQuoteRefreshService.refreshFundEstimate(fundCode);
        fundQuoteRefreshService.reconcileClosedTradingDay(fundCode);
        LocalDateTime now = LocalDateTime.now();
        LocalDate cachedTradingDay = intradayRedisCacheService.latestFundTradingDay(fundCode);
        LocalDate tradingDay = cachedTradingDay == null ? latestEstimateTradingDay(fundCode) : cachedTradingDay;
        if (isPreOpenClearWindow(now) && (tradingDay == null || !tradingDay.equals(now.toLocalDate()))) {
            return new FundDtos.MinuteSeriesResponse(
                    fund.fundCode(),
                    fund.fundName(),
                    now.toLocalDate(),
                    now,
                    "NO_DATA",
                    "ESTIMATE",
                    List.of()
            );
        }
        if (tradingDay == null) {
            return new FundDtos.MinuteSeriesResponse(
                    fund.fundCode(),
                    fund.fundName(),
                    null,
                    LocalDateTime.now(),
                    "NO_DATA",
                    "ESTIMATE",
                    List.of()
            );
        }

        List<FundDtos.EstimateMinutePointResponse> points = intradayRedisCacheService.loadFundMinutePoints(fundCode, tradingDay);
        if (points.isEmpty()) {
            points = jdbcTemplate.query("""
                    SELECT quote_time,
                           estimate_nav,
                           estimate_return_pct,
                           estimate_change_amount,
                           official_last_nav,
                           official_nav_date,
                           confidence_score,
                           data_lag_seconds
                    FROM fund_estimate_minute
                    WHERE fund_code = :fundCode AND trading_day = :tradingDay
                      AND TIME(quote_time) BETWEEN '09:30:00' AND '15:00:00'
                    ORDER BY quote_time
                    """, Map.of("fundCode", fundCode, "tradingDay", tradingDay), (rs, rowNum) ->
                    new FundDtos.EstimateMinutePointResponse(
                            localDateTime(rs, "quote_time"),
                            rs.getBigDecimal("estimate_nav"),
                            rs.getBigDecimal("estimate_return_pct"),
                            rs.getBigDecimal("estimate_change_amount"),
                            rs.getBigDecimal("official_last_nav"),
                            localDate(rs, "official_nav_date"),
                            rs.getBigDecimal("confidence_score"),
                            (Integer) rs.getObject("data_lag_seconds")
                    ));
        }
        if (points.isEmpty()) {
            points = loadSnapshotPoint(fundCode, tradingDay);
        }

        LocalDateTime updatedAt = points.isEmpty() ? LocalDateTime.now() : points.get(points.size() - 1).quoteTime();
        String seriesStatus = resolveMinuteSeriesStatus(fund.dataStatus(), points);
        FundDtos.MinuteSeriesResponse response = new FundDtos.MinuteSeriesResponse(
                fund.fundCode(),
                fund.fundName(),
                tradingDay,
                updatedAt,
                seriesStatus,
                "ESTIMATE",
                points
        );
        intradayRedisCacheService.cacheFundMinuteResponse(response);
        return response;
    }

    private List<FundDtos.EstimateMinutePointResponse> loadSnapshotPoint(String fundCode, LocalDate tradingDay) {
        if (tradingDay == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT quote_time,
                       estimate_nav,
                       estimate_return_pct,
                       data_lag_seconds
                FROM fund_realtime_snapshot
                WHERE fund_code = :fundCode AND trading_day = :tradingDay
                  AND TIME(quote_time) BETWEEN '09:30:00' AND '15:00:00'
                LIMIT 1
                """, Map.of("fundCode", fundCode, "tradingDay", tradingDay), (rs, rowNum) -> {
            java.math.BigDecimal estimateNav = rs.getBigDecimal("estimate_nav");
            java.math.BigDecimal estimateReturnPct = rs.getBigDecimal("estimate_return_pct");
            return new FundDtos.EstimateMinutePointResponse(
                    localDateTime(rs, "quote_time"),
                    estimateNav,
                    estimateReturnPct,
                    null,
                    null,
                    null,
                    null,
                    (Integer) rs.getObject("data_lag_seconds")
            );
        });
    }

    private String resolveMinuteSeriesStatus(String snapshotStatus, List<FundDtos.EstimateMinutePointResponse> points) {
        if (points == null || points.isEmpty()) {
            return "NO_DATA";
        }
        if (StringUtils.hasText(snapshotStatus)) {
            return snapshotStatus.trim().toUpperCase();
        }
        Integer lagSeconds = points.get(points.size() - 1).dataLagSeconds();
        if (lagSeconds == null) {
            return "NORMAL";
        }
        if (lagSeconds >= 900) {
            return "ERROR";
        }
        if (lagSeconds >= 180) {
            return "DELAYED";
        }
        return "NORMAL";
    }

    /**
     * 返回基金日净值历史走势。
     *
     * 近 1 月、3 月、6 月、1 年图表使用官方日净值，按交易日生成折线点；
     * 这和当日分时估算不同，不能用分钟点拼接，否则长期走势会失真。
     */
    public FundDtos.HistorySeriesResponse historySeries(String fundCode, String range) {
        FundDtos.DetailResponse fund = findByCode(fundCode);
        DateRange dateRange = dateRange(range);
        List<FundDtos.HistoryPointResponse> points = fundNavCacheService.loadHistoryRange(
                fundCode,
                dateRange.startDate(),
                dateRange.endDate()
        );

        return new FundDtos.HistorySeriesResponse(
                fund.fundCode(),
                fund.fundName(),
                dateRange.range(),
                dateRange.startDate(),
                dateRange.endDate(),
                LocalDateTime.now(),
                points.isEmpty() ? "NO_DATA" : "NORMAL",
                points
        );
    }

    @Transactional
    public FundDtos.DetailResponse create(FundDtos.CreateRequest request) {
        assertFundNotExists(request.fundCode());
        Long companyId = findOrCreateCompany(request.companyName(), request.companyShortName());
        Long custodianId = findOrCreateCustodian(request.custodianName(), request.custodianShortName());

        String fundType = normalizeEnum(request.fundType(), "OTHER", FUND_TYPES, "\u53c2\u6570\u4e0d\u5408\u6cd5");
        String operationMode = normalizeEnum(request.operationMode(), "OPEN", OPERATION_MODES, "\u53c2\u6570\u4e0d\u5408\u6cd5");
        String currency = StringUtils.hasText(request.currency()) ? request.currency().trim().toUpperCase() : "CNY";
        String fundShortName = trimToNull(request.fundShortName());

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO fund_product (
                  main_fund_code, fund_name, fund_short_name, fund_type, operation_mode, risk_level, currency,
                  inception_date, company_id, custodian_id, benchmark, tracking_index_code,
                  is_index_fund, is_qdii, is_fof, is_reit, status
                ) VALUES (
                  :fundCode, :fundName, :fundShortName, :fundType, :operationMode, :riskLevel, :currency,
                  :inceptionDate, :companyId, :custodianId, :benchmark, :trackingIndexCode,
                  :indexFund, :qdii, :fof, :reit, 'ACTIVE'
                )
                """, new MapSqlParameterSource()
                .addValue("fundCode", request.fundCode())
                .addValue("fundName", request.fundName().trim())
                .addValue("fundShortName", fundShortName)
                .addValue("fundType", fundType)
                .addValue("operationMode", operationMode)
                .addValue("riskLevel", trimToNull(request.riskLevel()))
                .addValue("currency", currency)
                .addValue("inceptionDate", request.inceptionDate())
                .addValue("companyId", companyId)
                .addValue("custodianId", custodianId)
                .addValue("benchmark", trimToNull(request.benchmark()))
                .addValue("trackingIndexCode", trimToNull(request.trackingIndexCode()))
                .addValue("indexFund", Boolean.TRUE.equals(request.indexFund()))
                .addValue("qdii", Boolean.TRUE.equals(request.qdii()))
                .addValue("fof", Boolean.TRUE.equals(request.fof()))
                .addValue("reit", Boolean.TRUE.equals(request.reit())), keyHolder);

        Long productId = extractGeneratedId(keyHolder, "基金产品");
        jdbcTemplate.update("""
                INSERT INTO fund_share_class (
                  product_id, fund_code, share_class_code, fund_name, fund_abbr, status
                ) VALUES (
                  :productId, :fundCode, :shareClassCode, :fundName, :fundAbbr, 'ACTIVE'
                )
                """, new MapSqlParameterSource()
                .addValue("productId", productId)
                .addValue("fundCode", request.fundCode())
                .addValue("shareClassCode", trimToNull(request.shareClassCode()))
                .addValue("fundName", request.fundName().trim())
                .addValue("fundAbbr", fundShortName));

        return findByCode(request.fundCode());
    }

    @Transactional
    public FundDtos.DetailResponse update(String fundCode, FundDtos.UpdateRequest request) {
        FundRef ref = getFundRef(fundCode);
        Long companyId = StringUtils.hasText(request.companyName())
                ? findOrCreateCompany(request.companyName(), request.companyShortName())
                : null;
        Long custodianId = StringUtils.hasText(request.custodianName())
                ? findOrCreateCustodian(request.custodianName(), request.custodianShortName())
                : null;

        jdbcTemplate.update("""
                UPDATE fund_product
                SET fund_name = COALESCE(:fundName, fund_name),
                    fund_short_name = COALESCE(:fundShortName, fund_short_name),
                    fund_type = COALESCE(:fundType, fund_type),
                    operation_mode = COALESCE(:operationMode, operation_mode),
                    risk_level = COALESCE(:riskLevel, risk_level),
                    currency = COALESCE(:currency, currency),
                    inception_date = COALESCE(:inceptionDate, inception_date),
                    company_id = COALESCE(:companyId, company_id),
                    custodian_id = COALESCE(:custodianId, custodian_id),
                    benchmark = COALESCE(:benchmark, benchmark),
                    tracking_index_code = COALESCE(:trackingIndexCode, tracking_index_code),
                    is_index_fund = COALESCE(:indexFund, is_index_fund),
                    is_qdii = COALESCE(:qdii, is_qdii),
                    is_fof = COALESCE(:fof, is_fof),
                    is_reit = COALESCE(:reit, is_reit)
                WHERE id = :productId
                """, new MapSqlParameterSource()
                .addValue("productId", ref.productId())
                .addValue("fundName", trimToNull(request.fundName()))
                .addValue("fundShortName", trimToNull(request.fundShortName()))
                .addValue("fundType", nullableEnum(request.fundType(), FUND_TYPES, "\u53c2\u6570\u4e0d\u5408\u6cd5"))
                .addValue("operationMode", nullableEnum(request.operationMode(), OPERATION_MODES, "\u53c2\u6570\u4e0d\u5408\u6cd5"))
                .addValue("riskLevel", trimToNull(request.riskLevel()))
                .addValue("currency", normalizeCurrency(request.currency()))
                .addValue("inceptionDate", request.inceptionDate())
                .addValue("companyId", companyId)
                .addValue("custodianId", custodianId)
                .addValue("benchmark", trimToNull(request.benchmark()))
                .addValue("trackingIndexCode", trimToNull(request.trackingIndexCode()))
                .addValue("indexFund", request.indexFund())
                .addValue("qdii", request.qdii())
                .addValue("fof", request.fof())
                .addValue("reit", request.reit()));

        jdbcTemplate.update("""
                UPDATE fund_share_class
                SET fund_name = COALESCE(:fundName, fund_name),
                    fund_abbr = COALESCE(:fundShortName, fund_abbr),
                    share_class_code = COALESCE(:shareClassCode, share_class_code),
                    purchase_status = COALESCE(:purchaseStatus, purchase_status),
                    redeem_status = COALESCE(:redeemStatus, redeem_status)
                WHERE fund_code = :fundCode
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("fundName", trimToNull(request.fundName()))
                .addValue("fundShortName", trimToNull(request.fundShortName()))
                .addValue("shareClassCode", trimToNull(request.shareClassCode()))
                .addValue("purchaseStatus", nullableEnum(request.purchaseStatus(), TRADE_STATUS, "申购状态不合法"))
                .addValue("redeemStatus", nullableEnum(request.redeemStatus(), TRADE_STATUS, "赎回状态不合法")));

        return findByCode(fundCode);
    }

    @Transactional
    public void deactivate(String fundCode) {
        getFundRef(fundCode);
        jdbcTemplate.update("""
                UPDATE fund_share_class
                SET status = 'TERMINATED'
                WHERE fund_code = :fundCode
                """, Map.of("fundCode", fundCode));
    }

    private FundDtos.DetailResponse findByCode(String fundCode) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                      fp.id AS product_id,
                      fsc.id AS share_class_id,
                      fsc.fund_code,
                      fsc.fund_name,
                      fsc.fund_abbr,
                      fsc.share_class_code,
                      fsc.purchase_status,
                      fsc.redeem_status,
                      fsc.status,
                      fp.fund_type,
                      fp.operation_mode,
                      fp.risk_level,
                      fp.currency,
                      fp.inception_date,
                      fp.benchmark,
                      fp.tracking_index_code,
                      fp.is_index_fund,
                      fp.is_qdii,
                      fp.is_fof,
                      fp.is_reit,
                      fc.company_name,
                      cst.custodian_name,
                      nav.nav_date,
                      nav.unit_nav,
                      nav.daily_return_pct,
                      snap.quote_time,
                      snap.estimate_nav,
                      snap.estimate_return_pct,
                      snap.confidence_score,
                      snap.data_status
                    FROM fund_share_class fsc
                    JOIN fund_product fp ON fp.id = fsc.product_id
                    LEFT JOIN fund_company fc ON fc.id = fp.company_id
                    LEFT JOIN fund_custodian cst ON cst.id = fp.custodian_id
                    LEFT JOIN (
                      SELECT n1.fund_code, n1.nav_date, n1.unit_nav, n1.daily_return_pct
                      FROM fund_nav_daily n1
                      JOIN (
                        SELECT fund_code, MAX(nav_date) AS nav_date
                        FROM fund_nav_daily
                        WHERE fund_code = :fundCode
                        GROUP BY fund_code
                      ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                    ) nav ON nav.fund_code = fsc.fund_code
                    LEFT JOIN fund_realtime_snapshot snap ON snap.fund_code = fsc.fund_code
                    WHERE fsc.fund_code = :fundCode
                    """, Map.of("fundCode", fundCode), FundRowMapper.detail());
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u57fa\u91d1\u4e0d\u5b58\u5728");
        }
    }

    private FundRef getFundRef(String fundCode) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id AS share_class_id, product_id
                    FROM fund_share_class
                    WHERE fund_code = :fundCode
                    """, Map.of("fundCode", fundCode), (rs, rowNum) -> new FundRef(
                    rs.getLong("share_class_id"),
                    rs.getLong("product_id")
            ));
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u57fa\u91d1\u4e0d\u5b58\u5728");
        }
    }

    private void assertFundNotExists(String fundCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fund_share_class WHERE fund_code = :fundCode",
                Map.of("fundCode", fundCode),
                Integer.class
        );
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "\u57fa\u91d1\u5df2\u5b58\u5728");
        }
    }

    private Long findOrCreateCompany(String companyName, String companyShortName) {
        String name = trimToNull(companyName);
        if (name == null) {
            return null;
        }
        Long existing = findIdByName("fund_company", "company_name", name);
        if (existing != null) {
            return existing;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO fund_company (company_name, company_short_name)
                VALUES (:companyName, :companyShortName)
                """, new MapSqlParameterSource()
                .addValue("companyName", name)
                .addValue("companyShortName", trimToNull(companyShortName)), keyHolder);
        return extractGeneratedId(keyHolder, "基金公司");
    }

    private Long findOrCreateCustodian(String custodianName, String custodianShortName) {
        String name = trimToNull(custodianName);
        if (name == null) {
            return null;
        }
        Long existing = findIdByName("fund_custodian", "custodian_name", name);
        if (existing != null) {
            return existing;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO fund_custodian (custodian_name, custodian_short_name)
                VALUES (:custodianName, :custodianShortName)
                """, new MapSqlParameterSource()
                .addValue("custodianName", name)
                .addValue("custodianShortName", trimToNull(custodianShortName)), keyHolder);
        return extractGeneratedId(keyHolder, "基金托管人");
    }

    private Long findIdByName(String tableName, String columnName, String name) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM " + tableName + " WHERE " + columnName + " = :name LIMIT 1",
                Map.of("name", name),
                (rs, rowNum) -> rs.getLong("id")
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private LocalDate latestEstimateTradingDay(String fundCode) {
        List<LocalDate> days = jdbcTemplate.query("""
                SELECT MAX(trading_day) AS trading_day
                FROM fund_estimate_minute
                WHERE fund_code = :fundCode
                  AND TIME(quote_time) BETWEEN '09:30:00' AND '15:00:00'
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> localDate(rs, "trading_day"));
        return days.isEmpty() ? null : days.get(0);
    }

    private DateRange dateRange(String range) {
        LocalDate endDate = LocalDate.now();
        String normalized = range == null || range.isBlank() ? "1m" : range.trim().toLowerCase();
        LocalDate startDate;
        switch (normalized) {
            case "3m" -> startDate = endDate.minusMonths(3);
            case "6m" -> startDate = endDate.minusMonths(6);
            case "1y" -> startDate = endDate.minusYears(1);
            default -> {
                normalized = "1m";
                startDate = endDate.minusMonths(1);
            }
        }
        return new DateRange(normalized, startDate, endDate);
    }

    private boolean isTradingTime(LocalDateTime now) {
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0)));
    }

    private boolean isPreOpenClearWindow(LocalDateTime now) {
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(LocalTime.of(8, 0)) && time.isBefore(LocalTime.of(9, 30));
    }

    private String normalizeEnum(String value, String defaultValue, Set<String> allowedValues, String errorMessage) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase() : defaultValue;
        if (!allowedValues.contains(normalized)) {
            throw new BusinessException(errorMessage);
        }
        return normalized;
    }

    private String nullableEnum(String value, Set<String> allowedValues, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!allowedValues.contains(normalized)) {
            throw new BusinessException(errorMessage);
        }
        return normalized;
    }

    private String normalizeCurrency(String currency) {
        if (!StringUtils.hasText(currency)) {
            return null;
        }
        return currency.trim().toUpperCase();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 兼容不同 JDBC 驱动返回的主键类型（Long / Integer / BigInteger 等）。
     */
    private Long extractGeneratedId(KeyHolder keyHolder, String entityName) {
        if (keyHolder == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, entityName + "创建失败");
        }
        List<Map<String, Object>> keyList = keyHolder.getKeyList();
        if (keyList == null || keyList.isEmpty()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, entityName + "创建失败");
        }
        Map<String, Object> firstRow = keyList.get(0);
        Object value = (firstRow == null || firstRow.isEmpty()) ? null : firstRow.values().iterator().next();
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException ignored) {
                // fallthrough
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, entityName + "创建失败");
    }

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record FundRef(Long shareClassId, Long productId) {
    }

    private record DateRange(String range, LocalDate startDate, LocalDate endDate) {
    }
}
