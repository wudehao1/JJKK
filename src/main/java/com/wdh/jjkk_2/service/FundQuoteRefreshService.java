package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.dto.FundDtos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.service.IntradayRedisCacheService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基金行情刷新、估算计算、官方净值同步和收盘校准服务。
 *
 * 场外基金没有完整实时成交价，因此估算逻辑分两层：先尝试公开源给出的估算数据，
 * 再根据基金披露持仓、股票实时涨跌、ETF/指数代理和剩余仓位暴露做本地估算修正。
 * 每分钟点先写入 Redis，保证用户打开详情页时读图很快；最新快照和需要长期保存的
 * 分时数据再写入 MySQL，供收盘后修正和历史回看使用。
 */
@Service
public class FundQuoteRefreshService {
    private static final Logger log = LoggerFactory.getLogger(FundQuoteRefreshService.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String EASTMONEY_ESTIMATE_URL = "https://fundgz.1234567.com.cn/js/";
    private static final String EASTMONEY_NAV_URL = "https://fundf10.eastmoney.com/F10DataApi.aspx";
    private static final String EASTMONEY_PINGZHONG_URL = "https://fund.eastmoney.com/pingzhongdata/";
    private static final String EASTMONEY_HOLDING_URL = "http://fundf10.eastmoney.com/FundArchivesDatas.aspx";
    private static final String EASTMONEY_FUND_SEARCH_URL = "http://fundsuggest.eastmoney.com/FundSearch/api/FundSearchAPI.ashx?m=9&key=";
    private static final String TENCENT_STOCK_QUOTE_URL = "http://qt.gtimg.cn/q=";
    private static final Duration FUND_EXPOSURE_CACHE_TTL = Duration.ofHours(2);
    private static final Duration TARGET_ETF_CACHE_TTL = Duration.ofHours(24);
    private static final Duration ESTIMATE_BIAS_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration DISCLOSURE_AGE_CACHE_TTL = Duration.ofHours(2);
    private static final DateTimeFormatter ESTIMATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Charset GBK = Charset.forName("GBK");
    private static final Pattern ROW_PATTERN = Pattern.compile("<tr>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL_PATTERN = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TENCENT_QUOTE_PATTERN = Pattern.compile("v_(sh|sz)(\\d{6})=\"([^\"]*)\";");
    private static final Pattern NET_WORTH_TREND_PATTERN = Pattern.compile(
            "var\\s+Data_netWorthTrend\\s*=\\s*(\\[.*?\\]);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ASSET_ALLOCATION_PATTERN = Pattern.compile(
            "var\\s+Data_assetAllocation\\s*=\\s*(\\{.*?\\});",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern REPORT_DATE_PATTERN = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern EASTMONEY_MARKET_CODE_PATTERN = Pattern.compile("unify/r/(\\d)\\.(\\d{6})");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final IntradayRedisCacheService intradayRedisCacheService;
    private final int minuteRefreshLimit;
    private final int refreshParallelism;
    private final int refreshBatchSize;
    private final int delayedLagThresholdSeconds;
    private final int errorLagThresholdSeconds;
    private final ExecutorService refreshExecutor;
    private final Map<String, CacheEntry<FundExposure>> exposureCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<TargetInstrument>> targetEtfCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<BigDecimal>> estimateBiasCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<Integer>> disclosureAgeCache = new ConcurrentHashMap<>();

    public FundQuoteRefreshService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            IntradayRedisCacheService intradayRedisCacheService,
            @Value("${jjkk.fund.minute-refresh-limit:1200}") int minuteRefreshLimit,
            @Value("${jjkk.fund.refresh-parallelism:6}") int refreshParallelism,
            @Value("${jjkk.fund.refresh-batch-size:180}") int refreshBatchSize,
            @Value("${jjkk.fund.delayed-lag-seconds:180}") int delayedLagThresholdSeconds,
            @Value("${jjkk.fund.error-lag-seconds:900}") int errorLagThresholdSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.intradayRedisCacheService = intradayRedisCacheService;
        this.minuteRefreshLimit = Math.max(1, minuteRefreshLimit);
        this.refreshParallelism = Math.max(1, refreshParallelism);
        this.refreshBatchSize = Math.max(this.refreshParallelism, refreshBatchSize);
        this.delayedLagThresholdSeconds = Math.max(30, delayedLagThresholdSeconds);
        this.errorLagThresholdSeconds = Math.max(this.delayedLagThresholdSeconds + 60, errorLagThresholdSeconds);
        this.refreshExecutor = Executors.newFixedThreadPool(this.refreshParallelism, new RefreshThreadFactory());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(4000);
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    @PreDestroy
    public void shutdownRefreshExecutor() {
        refreshExecutor.shutdown();
    }

    /**
     * 刷新单只基金当前估算，并写入最新快照。
     *
     * 如果当天最后分时点已经被官方真实净值校准，就不会再用过期估算覆盖真实结果。
     * 这能解决收盘后公开估算源滞后、但基金公司已经披露净值时前端仍显示估算的问题。
     */
    @Transactional
    public boolean refreshFundEstimate(String fundCode) {
        LocalDateTime now = LocalDateTime.now(CHINA_ZONE);
        LocalDate protectedClosedDay = isTradingMinute(now) ? now.toLocalDate() : latestClosedTradingDay();
        if (protectedClosedDay != null && hasClosedSnapshot(fundCode, protectedClosedDay)) {
            return false;
        }
        int sourceId = ensureDataSource(
                "EASTMONEY_FUND_ESTIMATE",
                "Eastmoney fund estimate",
                "https://fundgz.1234567.com.cn/",
                "Public network API for fund estimate minute chart"
        );
        String url = EASTMONEY_ESTIMATE_URL + fundCode + ".js";
        BigDecimal estimateNav = null;
        BigDecimal estimateReturnPct = null;
        BigDecimal officialEstimateReturnPct = null;
        BigDecimal officialLastNav = latestOfficialNav(fundCode);
        LocalDate officialNavDate = latestOfficialNavDate(fundCode);
        LocalDateTime sourceQuoteTime = now;

        try {
            String body = fetchUtf8Text(url).trim();
            int start = body.indexOf('(');
            int end = body.lastIndexOf(')');
            if (start >= 0 && end > start) {
                JsonNode root = objectMapper.readTree(body.substring(start + 1, end));
                estimateNav = decimal(root.path("gsz").asText(null));
                estimateReturnPct = decimal(root.path("gszzl").asText(null));
                officialEstimateReturnPct = estimateReturnPct;
                BigDecimal sourceLastNav = decimal(root.path("dwjz").asText(null));
                LocalDate sourceNavDate = parseDate(root.path("jzrq").asText(null));
                LocalDateTime parsedQuoteTime = parseEstimateTime(root.path("gztime").asText(null));
                if (sourceLastNav != null) {
                    officialLastNav = sourceLastNav;
                }
                if (sourceNavDate != null) {
                    officialNavDate = sourceNavDate;
                }
                sourceQuoteTime = parsedQuoteTime;
            }
        } catch (Exception ignored) {
            // 公开估算源异常时继续走本地持仓估算，避免单一上游失败导致分时图空白。
        }

        // 如果能根据披露持仓和代理标的算出结果，优先使用本地估算；
        // 这比完全依赖单个公开估算源更可控，也方便后续调优置信度和暴露权重。
        HoldingEstimate holdingEstimate = estimateByDisclosedPositions(fundCode, officialLastNav);
        BigDecimal confidenceScore = holdingEstimate == null ? null : holdingEstimate.confidenceScore();
        if (holdingEstimate != null) {
            estimateReturnPct = mergeEstimateReturnPct(
                    fundCode,
                    officialEstimateReturnPct,
                    holdingEstimate.estimateReturnPct(),
                    holdingEstimate.confidenceScore(),
                    sourceQuoteTime,
                    now
            );
            if (estimateReturnPct == null) {
                estimateReturnPct = holdingEstimate.estimateReturnPct();
            }
            if (officialLastNav != null && estimateReturnPct != null) {
                estimateNav = officialLastNav
                        .multiply(BigDecimal.ONE.add(estimateReturnPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)))
                        .setScale(6, RoundingMode.HALF_UP);
            } else {
                estimateNav = holdingEstimate.estimateNav();
            }
            confidenceScore = mergedConfidenceScore(
                    holdingEstimate.confidenceScore(),
                    officialEstimateReturnPct,
                    sourceQuoteTime,
                    now
            );
            if (isTradingMinute(now)) {
                sourceQuoteTime = now.truncatedTo(ChronoUnit.MINUTES);
            }
        } else if (isStaleForCurrentTradingMinute(sourceQuoteTime, now)) {
            estimateNav = null;
            estimateReturnPct = null;
            sourceQuoteTime = now.truncatedTo(ChronoUnit.MINUTES);
        }
        LocalDateTime quoteTime = displayQuoteTime(sourceQuoteTime);
        LocalDateTime minuteQuoteTime = normalizeMinuteQuoteTime(quoteTime);
        if (estimateNav == null
                && estimateReturnPct == null
                && officialLastNav != null
                && isOpeningBaselineMinute(minuteQuoteTime)) {
            estimateNav = officialLastNav;
            estimateReturnPct = BigDecimal.ZERO;
            sourceQuoteTime = minuteQuoteTime;
            holdingEstimate = new HoldingEstimate(estimateNav, estimateReturnPct, BigDecimal.valueOf(10));
            confidenceScore = holdingEstimate.confidenceScore();
        }
        if (estimateNav == null && estimateReturnPct != null && officialLastNav != null) {
            estimateNav = officialLastNav
                    .multiply(BigDecimal.ONE.add(estimateReturnPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)))
                    .setScale(6, RoundingMode.HALF_UP);
        }
        if (estimateReturnPct == null && estimateNav != null && officialLastNav != null) {
            estimateReturnPct = estimateNav.subtract(officialLastNav)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(officialLastNav, 6, RoundingMode.HALF_UP);
        }
        if (estimateNav == null && estimateReturnPct == null) {
            if (isTradingMinute(now)) {
                int lagSeconds = Math.max(errorLagThresholdSeconds, (int) Duration.between(sourceQuoteTime, now).abs().getSeconds());
                LocalDateTime staleTime = now.truncatedTo(ChronoUnit.MINUTES);
                upsertRealtimeSnapshotStatusOnly(
                        fundCode,
                        staleTime.toLocalDate(),
                        staleTime,
                        "ERROR",
                        lagSeconds
                );
            }
            return false;
        }

        BigDecimal estimateChangeAmount = estimateNav == null || officialLastNav == null
                ? null
                : estimateNav.subtract(officialLastNav);
        int dataLagSeconds = Math.max(0, (int) Duration.between(sourceQuoteTime, now).abs().getSeconds());
        String dataStatus = resolveRealtimeDataStatus(sourceQuoteTime, now, estimateNav, estimateReturnPct);

        jdbcTemplate.update("""
                INSERT INTO fund_realtime_snapshot (
                  fund_code, trading_day, quote_time, estimate_nav, estimate_return_pct,
                  confidence_score, data_status, data_lag_seconds
                ) VALUES (
                  :fundCode, :tradingDay, :quoteTime, :estimateNav, :estimateReturnPct,
                  :confidenceScore, :dataStatus, :dataLagSeconds
                )
                ON DUPLICATE KEY UPDATE
                  trading_day = VALUES(trading_day),
                  quote_time = VALUES(quote_time),
                  estimate_nav = VALUES(estimate_nav),
                  estimate_return_pct = VALUES(estimate_return_pct),
                  confidence_score = VALUES(confidence_score),
                  data_status = VALUES(data_status),
                  data_lag_seconds = VALUES(data_lag_seconds),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("tradingDay", minuteQuoteTime.toLocalDate())
                .addValue("quoteTime", minuteQuoteTime)
                .addValue("estimateNav", estimateNav)
                .addValue("estimateReturnPct", estimateReturnPct)
                .addValue("confidenceScore", confidenceScore)
                .addValue("dataStatus", dataStatus)
                .addValue("dataLagSeconds", dataLagSeconds));

        FundDtos.EstimateMinutePointResponse point = new FundDtos.EstimateMinutePointResponse(
                minuteQuoteTime,
                estimateNav,
                estimateReturnPct,
                estimateChangeAmount,
                officialLastNav,
                officialNavDate,
                confidenceScore,
                dataLagSeconds
        );
        boolean cached = intradayRedisCacheService.cacheFundMinutePoint(fundCode, minuteQuoteTime.toLocalDate(), point);
        if (!cached) {
            persistFundMinute(fundCode, minuteQuoteTime.toLocalDate(), point, sourceId);
        }
        return true;
    }

    @Transactional
    /**
     * 基金公司披露真实净值后，用官方结果修正当天最后一个分时点。
     *
     * 场外基金白天只能估算，收盘后如果官方日涨幅与估算尾点不一致，应以官方净值为准。
     * 该方法会更新估算快照、Redis 分时缓存和 MySQL 分时表，让前端刷新后看到“已更新”
     * 的真实涨幅。
     */
    public boolean reconcileClosedTradingDay(String fundCode) {
        LocalDate tradingDay = latestEstimateTradingDay(fundCode);
        LocalDate latestClosedTradingDay = latestClosedTradingDay();
        if (tradingDay == null || (latestClosedTradingDay != null && tradingDay.isBefore(latestClosedTradingDay))) {
            tradingDay = latestClosedTradingDay;
        }
        if (tradingDay == null || !isClosedForTradingDay(tradingDay)) {
            return false;
        }
        if (hasClosedSnapshot(fundCode, tradingDay)) {
            return false;
        }
        syncFundNavHistory(fundCode, tradingDay.minusDays(10), tradingDay);
        CloseNav closeNav = closeNav(fundCode, tradingDay);
        if (closeNav == null || closeNav.unitNav() == null) {
            return false;
        }
        BigDecimal previousNav = previousNav(fundCode, tradingDay);
        BigDecimal dailyReturnPct = closeNav.dailyReturnPct();
        if (dailyReturnPct == null && previousNav != null && BigDecimal.ZERO.compareTo(previousNav) != 0) {
            dailyReturnPct = closeNav.unitNav().subtract(previousNav)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(previousNav, 6, RoundingMode.HALF_UP);
        }
        BigDecimal estimateChangeAmount = previousNav == null ? null : closeNav.unitNav().subtract(previousNav);
        LocalDateTime closeTime = tradingDay.atTime(15, 0);
        int sourceId = ensureDataSource(
                "EASTMONEY_FUND_NAV",
                "Eastmoney fund NAV",
                "https://fundf10.eastmoney.com/",
                "Public network API for fund NAV history chart"
        );
        FundDtos.EstimateMinutePointResponse point = new FundDtos.EstimateMinutePointResponse(
                closeTime,
                closeNav.unitNav(),
                dailyReturnPct,
                estimateChangeAmount,
                previousNav,
                tradingDay,
                BigDecimal.valueOf(100),
                0
        );
        persistFundMinute(fundCode, tradingDay, point, sourceId);
        intradayRedisCacheService.cacheFundMinutePoint(fundCode, tradingDay, point);
        jdbcTemplate.update("""
                INSERT INTO fund_realtime_snapshot (
                  fund_code, trading_day, quote_time, estimate_nav, estimate_return_pct,
                  confidence_score, data_status, data_lag_seconds
                ) VALUES (
                  :fundCode, :tradingDay, :quoteTime, :estimateNav, :estimateReturnPct,
                  100, 'CLOSED', 0
                )
                ON DUPLICATE KEY UPDATE
                  trading_day = VALUES(trading_day),
                  quote_time = VALUES(quote_time),
                  estimate_nav = VALUES(estimate_nav),
                  estimate_return_pct = VALUES(estimate_return_pct),
                  confidence_score = VALUES(confidence_score),
                  data_status = VALUES(data_status),
                  data_lag_seconds = VALUES(data_lag_seconds),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("tradingDay", tradingDay)
                .addValue("quoteTime", closeTime)
                .addValue("estimateNav", closeNav.unitNav())
                .addValue("estimateReturnPct", dailyReturnPct));
        return true;
    }

    @Transactional
    /**
     * 同步东方财富披露的官方日净值到 {@code fund_nav_daily}。
     *
     * 这些数据用于基金近 1 月、3 月、6 月、1 年历史走势图，也用于收盘后把当日分时线
     * 的最后一个估算点校准为真实涨幅。写入使用唯一键 upsert，重复同步不会产生脏数据。
     */
    public int syncFundNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        int sourceId = ensureDataSource(
                "EASTMONEY_FUND_NAV",
                "Eastmoney fund NAV",
                "https://fundf10.eastmoney.com/",
                "Public network API for fund NAV history chart"
        );
        String trendUrl = EASTMONEY_PINGZHONG_URL + urlEncode(fundCode) + ".js";
        String tableUrl = EASTMONEY_NAV_URL
                + "?type=lsjz&code=" + urlEncode(fundCode)
                + "&page=1&per=1000"
                + "&sdate=" + startDate
                + "&edate=" + endDate;
        try {
            String sourceUrl = trendUrl;
            List<NavRecord> records = parseNetWorthTrend(fetchUtf8Text(trendUrl), startDate, endDate);
            if (records.isEmpty()) {
                sourceUrl = tableUrl;
                records = parseNavRows(fetchUtf8Text(tableUrl));
            }
            int count = 0;
            for (NavRecord record : records) {
                jdbcTemplate.update("""
                        INSERT INTO fund_nav_daily (
                          fund_code, nav_date, unit_nav, accumulated_nav, daily_return_pct,
                          source_id, source_url, source_updated_at
                        ) VALUES (
                          :fundCode, :navDate, :unitNav, :accumulatedNav, :dailyReturnPct,
                          :sourceId, :sourceUrl, CURRENT_TIMESTAMP(3)
                        )
                        ON DUPLICATE KEY UPDATE
                          unit_nav = VALUES(unit_nav),
                          accumulated_nav = VALUES(accumulated_nav),
                          daily_return_pct = VALUES(daily_return_pct),
                          source_id = VALUES(source_id),
                          source_url = VALUES(source_url),
                          source_updated_at = CURRENT_TIMESTAMP(3)
                        """, new MapSqlParameterSource()
                        .addValue("fundCode", fundCode)
                        .addValue("navDate", record.navDate())
                        .addValue("unitNav", record.unitNav())
                        .addValue("accumulatedNav", record.accumulatedNav())
                        .addValue("dailyReturnPct", record.dailyReturnPct())
                        .addValue("sourceId", sourceId)
                        .addValue("sourceUrl", sourceUrl));
                count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * 每分钟刷新一批活跃基金估算。
     *
     * 2 核 2G 服务器不能在每分钟内同时估算全市场 1.8 万只基金，所以这里按活跃自选、
     * 最近浏览、必要数量上限来控制刷新范围。这样优先保证真实用户正在看的基金不断点，
     * 后续用户规模扩大时再拆成队列或独立行情服务。
     */
    public void refreshActiveFundEstimates() {
        List<String> fundCodes = activeFundCodes();
        if (fundCodes.isEmpty()) {
            return;
        }
        AtomicInteger refreshed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        executeInParallelBatches(fundCodes, fundCode -> {
            try {
                if (refreshFundEstimate(fundCode)) {
                    refreshed.incrementAndGet();
                }
            } catch (Exception exception) {
                failed.incrementAndGet();
                log.debug("Refresh estimate failed, fundCode={}", fundCode, exception);
            }
        });
        if (failed.get() > 0) {
            log.warn("Active fund estimate refresh completed with partial failures, total={}, refreshed={}, failed={}",
                    fundCodes.size(), refreshed.get(), failed.get());
        }
    }

    public void reconcileActiveFundClosedMinutes() {
        LocalDate tradingDay = latestClosedTradingDay();
        List<String> fundCodes = activeFundCodesNeedingClosedReconciliation(tradingDay);
        if (fundCodes.isEmpty()) {
            return;
        }
        AtomicInteger reconciled = new AtomicInteger();
        executeInParallelBatches(fundCodes, fundCode -> {
            try {
                if (reconcileClosedTradingDay(fundCode)) {
                    reconciled.incrementAndGet();
                }
            } catch (Exception exception) {
                log.debug("Closed reconciliation failed, fundCode={}", fundCode, exception);
            }
        });
        log.debug("Closed reconciliation finished, tradingDay={}, total={}, reconciled={}",
                tradingDay, fundCodes.size(), reconciled.get());
    }

    private void executeInParallelBatches(List<String> fundCodes, Consumer<String> task) {
        if (fundCodes == null || fundCodes.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(Math.min(fundCodes.size(), refreshBatchSize));
        for (String fundCode : fundCodes) {
            futures.add(CompletableFuture.runAsync(() -> task.accept(fundCode), refreshExecutor));
            if (futures.size() >= refreshBatchSize) {
                waitForBatch(futures);
            }
        }
        waitForBatch(futures);
    }

    private void waitForBatch(List<CompletableFuture<Void>> futures) {
        if (futures.isEmpty()) {
            return;
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        futures.clear();
    }

    private List<String> activeFundCodesNeedingClosedReconciliation(LocalDate tradingDay) {
        if (tradingDay == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT DISTINCT fsc.fund_code
                FROM fund_share_class fsc
                LEFT JOIN user_watchlist w ON w.fund_code = fsc.fund_code
                LEFT JOIN user_fund_holding h ON h.fund_code = fsc.fund_code AND h.status = 'ACTIVE'
                LEFT JOIN fund_realtime_snapshot snap
                       ON snap.fund_code = fsc.fund_code
                      AND snap.trading_day = :tradingDay
                      AND snap.data_status = 'CLOSED'
                WHERE fsc.status = 'ACTIVE'
                  AND (w.id IS NOT NULL OR h.id IS NOT NULL)
                  AND snap.fund_code IS NULL
                ORDER BY fsc.fund_code
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tradingDay", tradingDay)
                .addValue("limit", minuteRefreshLimit), (rs, rowNum) -> rs.getString("fund_code"));
    }

    private List<String> activeFundCodes() {
        return jdbcTemplate.query("""
                SELECT DISTINCT fsc.fund_code
                FROM fund_share_class fsc
                LEFT JOIN user_watchlist w ON w.fund_code = fsc.fund_code
                LEFT JOIN user_fund_holding h ON h.fund_code = fsc.fund_code AND h.status = 'ACTIVE'
                WHERE fsc.status = 'ACTIVE' AND (w.id IS NOT NULL OR h.id IS NOT NULL)
                ORDER BY fsc.fund_code
                LIMIT :limit
                """, Map.of("limit", minuteRefreshLimit), (rs, rowNum) -> rs.getString("fund_code"));
    }

    public void clearTodayFundMinutes() {
        jdbcTemplate.update("""
                DELETE FROM fund_estimate_minute
                WHERE trading_day = :today
                """, Map.of("today", LocalDate.now(CHINA_ZONE)));
    }

    /**
     * 基于披露持仓和剩余仓位代理计算本地估算。
     *
     * 已披露股票按持仓占比乘以实时涨跌贡献收益；未披露或现金/债券/QDII 等剩余部分，
     * 结合基金类型、跟踪指数、目标 ETF 或市场代理做近似补偿。confidence_score 表示
     * 已知持仓和可解释代理覆盖了多少基金资产，覆盖越高，估算可信度越高。
     */
    private HoldingEstimate estimateByDisclosedPositions(String fundCode, BigDecimal officialLastNav) {
        List<PositionWeight> positions = latestStockPositions(fundCode);
        FundProfile profile = fundProfile(fundCode);
        FundExposure exposure = latestExposure(fundCode);
        boolean targetProxyEligible = isEtfLinked(profile) || isIndexProxy(profile);
        TargetInstrument targetInstrument = targetProxyEligible ? inferTargetEtf(profile) : null;
        BigDecimal targetChangePct = targetInstrument == null ? null : fetchTencentQuoteChange(targetInstrument.tencentCode());
        String benchmarkProxyCode = inferBenchmarkProxyCode(profile);
        BigDecimal benchmarkChangePct = targetChangePct == null ? fetchTencentQuoteChange(benchmarkProxyCode) : null;
        boolean indexProxy = targetChangePct != null && isIndexProxy(profile);
        if (positions.isEmpty() && targetChangePct == null && benchmarkChangePct == null) {
            return null;
        }

        Map<String, BigDecimal> quoteChanges = positions.isEmpty() ? Map.of() : fetchTencentStockChanges(positions);
        if (!positions.isEmpty() && quoteChanges.isEmpty() && targetChangePct == null && benchmarkChangePct == null) {
            return null;
        }

        BigDecimal stockWeightedReturnPct = BigDecimal.ZERO;
        BigDecimal activeWeightPct = BigDecimal.ZERO;
        for (PositionWeight position : positions) {
            BigDecimal changePct = quoteChanges.get(position.tencentCode());
            if (changePct == null) {
                continue;
            }
            BigDecimal weightFraction = navWeightFraction(position.navRatio());
            stockWeightedReturnPct = stockWeightedReturnPct.add(changePct.multiply(weightFraction));
            activeWeightPct = activeWeightPct.add(weightFraction.multiply(BigDecimal.valueOf(100)));
        }

        BigDecimal weightedReturnPct = stockWeightedReturnPct;
        BigDecimal coveragePct = activeWeightPct;
        BigDecimal stockExposurePct = normalizedPct(exposure.stockPct());

        if (indexProxy) {
            BigDecimal targetWeightPct = targetProxyWeight(exposure, BigDecimal.ZERO, true);
            weightedReturnPct = targetChangePct.multiply(navWeightFraction(targetWeightPct));
            coveragePct = targetWeightPct;
        } else if (targetChangePct != null) {
            BigDecimal targetWeightPct = targetProxyWeight(exposure, coveragePct, positions.isEmpty() || isEtfLinked(profile));
            if (targetWeightPct.compareTo(BigDecimal.ZERO) > 0) {
                weightedReturnPct = weightedReturnPct.add(targetChangePct.multiply(navWeightFraction(targetWeightPct)));
                coveragePct = coveragePct.add(targetWeightPct);
            }
        } else if (benchmarkChangePct != null) {
            BigDecimal benchmarkWeightPct = targetProxyWeight(exposure, coveragePct, positions.isEmpty());
            if (benchmarkWeightPct.compareTo(BigDecimal.ZERO) > 0) {
                weightedReturnPct = weightedReturnPct.add(benchmarkChangePct.multiply(navWeightFraction(benchmarkWeightPct)));
                coveragePct = coveragePct.add(benchmarkWeightPct);
            }
        } else if (stockExposurePct != null
                && stockExposurePct.compareTo(activeWeightPct) > 0
                && activeWeightPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal cappedCoverage = stockExposurePct.min(activeWeightPct.multiply(BigDecimal.valueOf(1.8)));
            BigDecimal scale = cappedCoverage.divide(activeWeightPct, 10, RoundingMode.HALF_UP);
            weightedReturnPct = stockWeightedReturnPct.multiply(scale);
            coveragePct = cappedCoverage;
        } else if (stockExposurePct == null
                && activeWeightPct.compareTo(BigDecimal.ZERO) > 0
                && activeWeightPct.compareTo(BigDecimal.valueOf(65)) < 0) {
            BigDecimal cappedCoverage = activeWeightPct.multiply(BigDecimal.valueOf(1.35)).min(BigDecimal.valueOf(80));
            BigDecimal scale = cappedCoverage.divide(activeWeightPct, 10, RoundingMode.HALF_UP);
            weightedReturnPct = stockWeightedReturnPct.multiply(scale);
            coveragePct = cappedCoverage;
        }

        if (BigDecimal.ZERO.compareTo(coveragePct) == 0) {
            return null;
        }

        BigDecimal baseNav = officialLastNav == null ? latestOfficialNav(fundCode) : officialLastNav;
        BigDecimal estimateReturnPct = weightedReturnPct.setScale(6, RoundingMode.HALF_UP);
        BigDecimal estimateNav = baseNav == null
                ? null
                : baseNav.multiply(BigDecimal.ONE.add(estimateReturnPct.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)))
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal confidence = BigDecimal.valueOf(Math.min(96, 35 + coveragePct.doubleValue() * 0.62))
                .multiply(disclosureFreshnessFactor(fundCode))
                .max(BigDecimal.valueOf(22));
        if (activeWeightPct.compareTo(BigDecimal.ZERO) <= 0) {
            confidence = confidence.multiply(BigDecimal.valueOf(0.78));
        }
        if (targetChangePct == null && benchmarkChangePct != null) {
            confidence = confidence.multiply(BigDecimal.valueOf(0.85));
        }
        confidence = confidence
                .max(BigDecimal.valueOf(20))
                .min(BigDecimal.valueOf(96))
                .setScale(2, RoundingMode.HALF_UP);
        return new HoldingEstimate(estimateNav, estimateReturnPct, confidence);
    }

    private BigDecimal mergeEstimateReturnPct(
            String fundCode,
            BigDecimal officialEstimateReturnPct,
            BigDecimal holdingEstimateReturnPct,
            BigDecimal holdingConfidence,
            LocalDateTime officialQuoteTime,
            LocalDateTime now
    ) {
        if (holdingEstimateReturnPct == null) {
            return officialEstimateReturnPct;
        }
        BigDecimal adjustedHolding = applyRecentBiasCorrection(fundCode, holdingEstimateReturnPct, holdingConfidence, now);
        if (officialEstimateReturnPct == null) {
            return adjustedHolding;
        }
        BigDecimal holdingWeight = holdingSignalWeight(fundCode, holdingConfidence, now);
        BigDecimal officialWeight = officialSignalWeight(officialQuoteTime, now);
        BigDecimal spread = adjustedHolding.subtract(officialEstimateReturnPct).abs();
        if (spread.compareTo(BigDecimal.valueOf(1.8)) > 0) {
            BigDecimal damping = spread.min(BigDecimal.valueOf(4))
                    .divide(BigDecimal.TEN, 10, RoundingMode.HALF_UP);
            holdingWeight = holdingWeight.multiply(BigDecimal.ONE.subtract(damping))
                    .max(BigDecimal.valueOf(0.12));
        }
        BigDecimal totalWeight = holdingWeight.add(officialWeight);
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return adjustedHolding;
        }
        return adjustedHolding.multiply(holdingWeight)
                .add(officialEstimateReturnPct.multiply(officialWeight))
                .divide(totalWeight, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal mergedConfidenceScore(
            BigDecimal holdingConfidence,
            BigDecimal officialEstimateReturnPct,
            LocalDateTime officialQuoteTime,
            LocalDateTime now
    ) {
        if (holdingConfidence == null) {
            return null;
        }
        if (officialEstimateReturnPct == null) {
            return holdingConfidence;
        }
        BigDecimal officialWeight = officialSignalWeight(officialQuoteTime, now);
        BigDecimal officialConfidence = BigDecimal.valueOf(72)
                .add(officialWeight.multiply(BigDecimal.valueOf(18)))
                .min(BigDecimal.valueOf(90));
        BigDecimal holdWeight = BigDecimal.valueOf(0.6);
        BigDecimal offWeight = BigDecimal.valueOf(0.4);
        return holdingConfidence.multiply(holdWeight)
                .add(officialConfidence.multiply(offWeight))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal applyRecentBiasCorrection(
            String fundCode,
            BigDecimal holdingEstimateReturnPct,
            BigDecimal holdingConfidence,
            LocalDateTime now
    ) {
        BigDecimal biasPct = recentCloseBiasPct(fundCode);
        if (biasPct == null || biasPct.abs().compareTo(BigDecimal.valueOf(0.02)) < 0) {
            return holdingEstimateReturnPct;
        }
        BigDecimal progress = intradayProgress(now);
        BigDecimal confidenceFactor;
        if (holdingConfidence == null) {
            confidenceFactor = BigDecimal.valueOf(0.55);
        } else {
            confidenceFactor = BigDecimal.ONE.subtract(
                    holdingConfidence.min(BigDecimal.valueOf(95))
                            .divide(BigDecimal.valueOf(120), 10, RoundingMode.HALF_UP)
            ).max(BigDecimal.valueOf(0.12));
        }
        BigDecimal strength = BigDecimal.valueOf(0.15)
                .add(progress.multiply(BigDecimal.valueOf(0.45)))
                .multiply(confidenceFactor)
                .min(BigDecimal.valueOf(0.55));
        BigDecimal adjustment = biasPct.multiply(strength).setScale(6, RoundingMode.HALF_UP);
        return holdingEstimateReturnPct.add(adjustment).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal holdingSignalWeight(String fundCode, BigDecimal holdingConfidence, LocalDateTime now) {
        BigDecimal confidenceWeight = holdingConfidence == null
                ? BigDecimal.valueOf(0.55)
                : holdingConfidence.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal progressBoost = BigDecimal.valueOf(0.70)
                .add(intradayProgress(now).multiply(BigDecimal.valueOf(0.30)));
        BigDecimal weight = confidenceWeight
                .multiply(disclosureFreshnessFactor(fundCode))
                .multiply(progressBoost);
        return weight.max(BigDecimal.valueOf(0.15)).min(BigDecimal.valueOf(1.20));
    }

    private BigDecimal officialSignalWeight(LocalDateTime officialQuoteTime, LocalDateTime now) {
        if (officialQuoteTime == null) {
            return BigDecimal.valueOf(0.30);
        }
        if (!officialQuoteTime.toLocalDate().equals(now.toLocalDate())) {
            return BigDecimal.valueOf(0.25);
        }
        long lagMinutes = Math.abs(Duration.between(officialQuoteTime, now).toMinutes());
        if (lagMinutes <= 5) {
            return BigDecimal.valueOf(1.05);
        }
        if (lagMinutes <= 15) {
            return BigDecimal.valueOf(0.92);
        }
        if (lagMinutes <= 30) {
            return BigDecimal.valueOf(0.78);
        }
        if (lagMinutes <= 60) {
            return BigDecimal.valueOf(0.62);
        }
        return BigDecimal.valueOf(0.45);
    }

    private BigDecimal intradayProgress(LocalDateTime time) {
        if (time == null) {
            return BigDecimal.ONE;
        }
        if (!isTradingMinute(time)) {
            return BigDecimal.ONE;
        }
        LocalDate day = time.toLocalDate();
        LocalDateTime morningOpen = day.atTime(9, 30);
        LocalDateTime morningClose = day.atTime(11, 30);
        LocalDateTime afternoonOpen = day.atTime(13, 0);
        LocalDateTime afternoonClose = day.atTime(15, 0);
        long elapsedMinutes;
        if (!time.isAfter(morningClose)) {
            elapsedMinutes = Math.max(0, Duration.between(morningOpen, time).toMinutes());
        } else if (!time.isBefore(afternoonOpen)) {
            elapsedMinutes = 120 + Math.max(0, Duration.between(afternoonOpen, time).toMinutes());
        } else {
            elapsedMinutes = 120;
        }
        long bounded = Math.max(0, Math.min(240, elapsedMinutes));
        return BigDecimal.valueOf(bounded)
                .divide(BigDecimal.valueOf(240), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal disclosureFreshnessFactor(String fundCode) {
        Integer ageDays = latestDisclosureAgeDays(fundCode);
        if (ageDays == null) {
            return BigDecimal.valueOf(0.75);
        }
        if (ageDays <= 45) {
            return BigDecimal.ONE;
        }
        if (ageDays <= 90) {
            return BigDecimal.valueOf(0.92);
        }
        if (ageDays <= 150) {
            return BigDecimal.valueOf(0.82);
        }
        if (ageDays <= 210) {
            return BigDecimal.valueOf(0.72);
        }
        if (ageDays <= 300) {
            return BigDecimal.valueOf(0.62);
        }
        return BigDecimal.valueOf(0.55);
    }

    private Integer latestDisclosureAgeDays(String fundCode) {
        CacheEntry<Integer> cached = disclosureAgeCache.get(fundCode);
        if (isFresh(cached, DISCLOSURE_AGE_CACHE_TTL)) {
            return cached.value();
        }
        List<Integer> days = jdbcTemplate.query("""
                SELECT COALESCE(r.report_date, r.publish_date) AS report_day
                FROM fund_share_class fsc
                JOIN fund_disclosure_report r ON r.product_id = fsc.product_id
                WHERE fsc.fund_code = :fundCode
                ORDER BY COALESCE(r.report_date, r.publish_date) DESC, r.id DESC
                LIMIT 1
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> {
            java.sql.Date reportDay = rs.getDate("report_day");
            if (reportDay == null) {
                return null;
            }
            return (int) ChronoUnit.DAYS.between(reportDay.toLocalDate(), LocalDate.now(CHINA_ZONE));
        });
        Integer ageDays = days.isEmpty() ? null : days.get(0);
        disclosureAgeCache.put(fundCode, new CacheEntry<>(ageDays, LocalDateTime.now()));
        return ageDays;
    }

    private BigDecimal recentCloseBiasPct(String fundCode) {
        CacheEntry<BigDecimal> cached = estimateBiasCache.get(fundCode);
        if (isFresh(cached, ESTIMATE_BIAS_CACHE_TTL)) {
            return cached.value();
        }
        LocalDate startDay = LocalDate.now(CHINA_ZONE).minusDays(40);
        List<CloseEstimateGap> gaps = jdbcTemplate.query("""
                SELECT m.estimate_return_pct, n.daily_return_pct
                FROM fund_estimate_minute m
                JOIN (
                  SELECT trading_day, MAX(quote_time) AS quote_time
                  FROM fund_estimate_minute
                  WHERE fund_code = :fundCode
                    AND estimate_return_pct IS NOT NULL
                    AND trading_day >= :startDay
                    AND TIME(quote_time) < '15:00:00'
                  GROUP BY trading_day
                ) latest ON latest.trading_day = m.trading_day
                        AND latest.quote_time = m.quote_time
                JOIN fund_nav_daily n
                  ON n.fund_code = :fundCode
                 AND n.nav_date = m.trading_day
                WHERE m.fund_code = :fundCode
                  AND n.daily_return_pct IS NOT NULL
                ORDER BY m.trading_day DESC
                LIMIT 12
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("startDay", startDay), (rs, rowNum) -> new CloseEstimateGap(
                rs.getBigDecimal("estimate_return_pct"),
                rs.getBigDecimal("daily_return_pct")
        ));
        BigDecimal weight = BigDecimal.ONE;
        BigDecimal weightedGap = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (CloseEstimateGap gap : gaps) {
            if (gap.estimateReturnPct() == null || gap.officialReturnPct() == null) {
                continue;
            }
            BigDecimal diff = gap.officialReturnPct().subtract(gap.estimateReturnPct());
            if (diff.abs().compareTo(BigDecimal.valueOf(6)) > 0) {
                continue;
            }
            weightedGap = weightedGap.add(diff.multiply(weight));
            totalWeight = totalWeight.add(weight);
            weight = weight.multiply(BigDecimal.valueOf(0.78));
        }
        BigDecimal bias = totalWeight.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : weightedGap.divide(totalWeight, 6, RoundingMode.HALF_UP);
        estimateBiasCache.put(fundCode, new CacheEntry<>(bias, LocalDateTime.now()));
        return bias;
    }

    private List<PositionWeight> latestStockPositions(String fundCode) {
        List<PositionWeight> positions = loadLatestStockPositions(fundCode);
        if (positions.isEmpty()) {
            syncLatestDisclosedStockPositions(fundCode);
            positions = loadLatestStockPositions(fundCode);
        }
        return positions;
    }

    private List<PositionWeight> loadLatestStockPositions(String fundCode) {
        return jdbcTemplate.query("""
                SELECT p.market, p.security_code, p.nav_ratio
                FROM fund_position_disclosure p
                JOIN (
                  SELECT r.id
                  FROM fund_share_class fsc
                  JOIN fund_disclosure_report r ON r.product_id = fsc.product_id
                  WHERE fsc.fund_code = :fundCode
                  ORDER BY COALESCE(r.report_date, r.publish_date) DESC, r.id DESC
                  LIMIT 1
                ) latest ON latest.id = p.report_id
                WHERE p.asset_type = 'STOCK'
                  AND p.security_code REGEXP '^[0-9]{6}$'
                  AND p.nav_ratio IS NOT NULL
                ORDER BY p.nav_ratio DESC, p.rank_no
                LIMIT 80
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> {
            String tencentCode = tencentStockCode(rs.getString("market"), rs.getString("security_code"));
            return tencentCode == null ? null : new PositionWeight(tencentCode, rs.getBigDecimal("nav_ratio"));
        }).stream().filter(item -> item != null).toList();
    }

    private int syncLatestDisclosedStockPositions(String fundCode) {
        int sourceId = ensureDataSource(
                "EASTMONEY_FUND_HOLDING",
                "Eastmoney fund holding",
                "http://fundf10.eastmoney.com/",
                "Public fund holding disclosure imported for intraday estimate"
        );
        String sourceUrl = EASTMONEY_HOLDING_URL
                + "?type=jjcc&code=" + urlEncode(fundCode)
                + "&topline=80&year=&month=3";
        try {
            String body = fetchUtf8Text(sourceUrl);
            Matcher dateMatcher = REPORT_DATE_PATTERN.matcher(body);
            if (!dateMatcher.find()) {
                return 0;
            }
            LocalDate reportDate = LocalDate.parse(dateMatcher.group(0));
            Long reportId = ensureDisclosureReport(fundCode, reportDate, sourceId, sourceUrl);
            if (reportId == null) {
                return 0;
            }
            jdbcTemplate.update("""
                    DELETE FROM fund_position_disclosure
                    WHERE report_id = :reportId AND asset_type = 'STOCK'
                    """, Map.of("reportId", reportId));

            Matcher rowMatcher = ROW_PATTERN.matcher(body);
            int count = 0;
            while (rowMatcher.find()) {
                String row = rowMatcher.group(1);
                Matcher cellMatcher = CELL_PATTERN.matcher(row);
                List<String> cells = new ArrayList<>();
                while (cellMatcher.find()) {
                    cells.add(stripTags(cellMatcher.group(1)));
                }
                if (cells.size() < 7) {
                    continue;
                }
                String code = normalizeSecurityCode(cells.get(1));
                if (code == null) {
                    continue;
                }
                String market = marketFromEastmoneyRow(row, code);
                BigDecimal navRatio = decimalLoose(cells.get(6));
                if (navRatio == null) {
                    continue;
                }
                jdbcTemplate.update("""
                        INSERT INTO fund_position_disclosure (
                          report_id, market, security_code, security_name, asset_type,
                          rank_no, holding_quantity, market_value, nav_ratio
                        ) VALUES (
                          :reportId, :market, :securityCode, :securityName, 'STOCK',
                          :rankNo, :holdingQuantity, :marketValue, :navRatio
                        )
                        ON DUPLICATE KEY UPDATE
                          market = VALUES(market),
                          security_name = VALUES(security_name),
                          holding_quantity = VALUES(holding_quantity),
                          market_value = VALUES(market_value),
                          nav_ratio = VALUES(nav_ratio)
                        """, new MapSqlParameterSource()
                        .addValue("reportId", reportId)
                        .addValue("market", market)
                        .addValue("securityCode", code)
                        .addValue("securityName", cells.get(2))
                        .addValue("rankNo", integer(cells.get(0)))
                        .addValue("holdingQuantity", cells.size() > 7 ? decimalLoose(cells.get(7)) : null)
                        .addValue("marketValue", cells.size() > 8 ? decimalLoose(cells.get(8)) : null)
                        .addValue("navRatio", navRatio));
                count++;
            }
            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Long ensureDisclosureReport(String fundCode, LocalDate reportDate, int sourceId, String sourceUrl) {
        List<Long> productIds = jdbcTemplate.query("""
                SELECT product_id
                FROM fund_share_class
                WHERE fund_code = :fundCode
                LIMIT 1
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> rs.getLong("product_id"));
        if (productIds.isEmpty()) {
            return null;
        }
        String period = reportDate.getYear() + "Q" + ((reportDate.getMonthValue() - 1) / 3 + 1);
        jdbcTemplate.update("""
                INSERT INTO fund_disclosure_report (
                  product_id, report_type, report_period, report_date, title, source_id, source_url
                ) VALUES (
                  :productId, 'QUARTER', :reportPeriod, :reportDate, :title, :sourceId, :sourceUrl
                )
                ON DUPLICATE KEY UPDATE
                  report_date = VALUES(report_date),
                  title = VALUES(title),
                  source_id = VALUES(source_id),
                  source_url = VALUES(source_url),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("productId", productIds.get(0))
                .addValue("reportPeriod", period)
                .addValue("reportDate", reportDate)
                .addValue("title", fundCode + " holding disclosure " + period)
                .addValue("sourceId", sourceId)
                .addValue("sourceUrl", sourceUrl));
        return jdbcTemplate.queryForObject("""
                SELECT id
                FROM fund_disclosure_report
                WHERE product_id = :productId
                  AND report_type = 'QUARTER'
                  AND report_period = :reportPeriod
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("productId", productIds.get(0))
                .addValue("reportPeriod", period), Long.class);
    }

    private Map<String, BigDecimal> fetchTencentStockChanges(List<PositionWeight> positions) {
        String query = positions.stream()
                .map(PositionWeight::tencentCode)
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (query.isBlank()) {
            return Map.of();
        }
        try {
            Matcher matcher = TENCENT_QUOTE_PATTERN.matcher(fetchGbkText(TENCENT_STOCK_QUOTE_URL + query));
            Map<String, BigDecimal> changes = new java.util.HashMap<>();
            while (matcher.find()) {
                String code = matcher.group(1) + matcher.group(2);
                String[] fields = matcher.group(3).split("~", -1);
                if (fields.length > 32) {
                    BigDecimal changePct = decimal(fields[32]);
                    if (changePct != null) {
                        changes.put(code, changePct);
                    }
                }
            }
            return changes;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private BigDecimal fetchTencentQuoteChange(String tencentCode) {
        if (tencentCode == null || tencentCode.isBlank()) {
            return null;
        }
        try {
            Matcher matcher = TENCENT_QUOTE_PATTERN.matcher(fetchGbkText(TENCENT_STOCK_QUOTE_URL + tencentCode));
            if (!matcher.find()) {
                return null;
            }
            String[] fields = matcher.group(3).split("~", -1);
            return fields.length > 32 ? decimal(fields[32]) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal navWeightFraction(BigDecimal navRatio) {
        if (navRatio == null) {
            return BigDecimal.ZERO;
        }
        if (navRatio.abs().compareTo(BigDecimal.ONE) <= 0) {
            return navRatio;
        }
        return navRatio.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
    }

    private String tencentStockCode(String market, String securityCode) {
        if (securityCode == null || securityCode.length() != 6) {
            return null;
        }
        String normalizedMarket = market == null ? "" : market.toUpperCase();
        if (normalizedMarket.contains("SSE") || normalizedMarket.contains("SH")) {
            return "sh" + securityCode;
        }
        if (normalizedMarket.contains("SZSE") || normalizedMarket.contains("SZ")) {
            return "sz" + securityCode;
        }
        char first = securityCode.charAt(0);
        if (first == '6' || first == '5' || first == '9') {
            return "sh" + securityCode;
        }
        if (first == '0' || first == '1' || first == '2' || first == '3') {
            return "sz" + securityCode;
        }
        return null;
    }

    private FundExposure latestExposure(String fundCode) {
        CacheEntry<FundExposure> cached = exposureCache.get(fundCode);
        if (isFresh(cached, FUND_EXPOSURE_CACHE_TTL)) {
            return cached.value();
        }
        try {
            Matcher matcher = ASSET_ALLOCATION_PATTERN.matcher(fetchUtf8Text(EASTMONEY_PINGZHONG_URL + urlEncode(fundCode) + ".js"));
            if (!matcher.find()) {
                FundExposure empty = FundExposure.empty();
                exposureCache.put(fundCode, new CacheEntry<>(empty, LocalDateTime.now()));
                return empty;
            }
            JsonNode root = objectMapper.readTree(matcher.group(1));
            BigDecimal stockPct = null;
            BigDecimal bondPct = null;
            BigDecimal cashPct = null;
            JsonNode series = root.path("series");
            if (series.isArray()) {
                for (JsonNode item : series) {
                    String name = item.path("name").asText("");
                    BigDecimal lastValue = lastNumber(item.path("data"));
                    if (name.contains("鑲＄エ")) {
                        stockPct = lastValue;
                    } else if (name.contains("鍊哄埜")) {
                        bondPct = lastValue;
                    } else if (name.contains("鐜伴噾")) {
                        cashPct = lastValue;
                    }
                }
            }
            FundExposure exposure = new FundExposure(stockPct, bondPct, cashPct);
            exposureCache.put(fundCode, new CacheEntry<>(exposure, LocalDateTime.now()));
            return exposure;
        } catch (Exception ignored) {
            FundExposure empty = FundExposure.empty();
            exposureCache.put(fundCode, new CacheEntry<>(empty, LocalDateTime.now()));
            return empty;
        }
    }

    private BigDecimal targetEtfWeight(FundExposure exposure, BigDecimal knownCoveragePct) {
        BigDecimal residual = BigDecimal.valueOf(100);
        residual = subtractPct(residual, exposure.stockPct());
        residual = subtractPct(residual, exposure.bondPct());
        residual = subtractPct(residual, exposure.cashPct());
        if (residual.compareTo(BigDecimal.ZERO) <= 0) {
            residual = BigDecimal.valueOf(100).subtract(knownCoveragePct == null ? BigDecimal.ZERO : knownCoveragePct);
        }
        if (residual.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return residual.min(BigDecimal.valueOf(98)).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal targetProxyWeight(FundExposure exposure, BigDecimal knownCoveragePct, boolean fullProxy) {
        BigDecimal known = knownCoveragePct == null ? BigDecimal.ZERO : knownCoveragePct.max(BigDecimal.ZERO);
        BigDecimal stockPct = normalizedPct(exposure.stockPct());
        if (fullProxy) {
            if (stockPct != null && stockPct.compareTo(BigDecimal.ZERO) > 0) {
                return stockPct.min(BigDecimal.valueOf(98));
            }
            BigDecimal fundAssetWeight = targetEtfWeight(exposure, known);
            if (fundAssetWeight.compareTo(BigDecimal.ZERO) > 0) {
                return fundAssetWeight;
            }
            return BigDecimal.valueOf(95);
        }
        if (stockPct == null) {
            return BigDecimal.ZERO;
        }
        return stockPct.subtract(known).max(BigDecimal.ZERO).min(BigDecimal.valueOf(98));
    }

    private BigDecimal normalizedPct(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal pct = value.abs().compareTo(BigDecimal.ONE) <= 0
                ? value.multiply(BigDecimal.valueOf(100))
                : value;
        return pct.max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal subtractPct(BigDecimal base, BigDecimal value) {
        BigDecimal pct = normalizedPct(value);
        return pct == null ? base : base.subtract(pct);
    }

    private BigDecimal lastNumber(JsonNode data) {
        if (!data.isArray() || data.isEmpty()) {
            return null;
        }
        for (int index = data.size() - 1; index >= 0; index--) {
            JsonNode node = data.get(index);
            BigDecimal value = decimal(node.asText(null));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private FundProfile fundProfile(String fundCode) {
        List<FundProfile> values = jdbcTemplate.query("""
                SELECT fsc.fund_code,
                       fsc.fund_name,
                       COALESCE(fp.fund_short_name, fsc.fund_abbr, fsc.fund_name) AS fund_short_name,
                       COALESCE(fc.company_short_name, fc.company_name, '') AS company_name,
                       fp.benchmark,
                       fp.tracking_index_code,
                       fp.is_index_fund
                FROM fund_share_class fsc
                JOIN fund_product fp ON fp.id = fsc.product_id
                LEFT JOIN fund_company fc ON fc.id = fp.company_id
                WHERE fsc.fund_code = :fundCode
                LIMIT 1
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> new FundProfile(
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("fund_short_name"),
                rs.getString("company_name"),
                rs.getString("benchmark"),
                rs.getString("tracking_index_code"),
                rs.getBoolean("is_index_fund")
        ));
        return values.isEmpty() ? new FundProfile(fundCode, "", "", "", "", "", false) : values.get(0);
    }

    private boolean isEtfLinked(FundProfile profile) {
        String name = (profile.fundName() + " " + profile.fundShortName()).toUpperCase();
        return name.contains("ETF") && name.contains("鑱旀帴");
    }

    private boolean isIndexProxy(FundProfile profile) {
        String name = (profile.fundName() + " " + profile.fundShortName()).toUpperCase();
        return Boolean.TRUE.equals(profile.indexFund())
                || (profile.trackingIndexCode() != null && !profile.trackingIndexCode().isBlank())
                || name.contains("鎸囨暟");
    }

    private String inferBenchmarkProxyCode(FundProfile profile) {
        String fromTracking = tencentIndexCodeFromAny(profile.trackingIndexCode());
        if (fromTracking != null) {
            return fromTracking;
        }
        String fromBenchmark = tencentIndexCodeFromAny(profile.benchmark());
        if (fromBenchmark != null) {
            return fromBenchmark;
        }
        String joined = (profile.fundName() + " " + profile.fundShortName() + " " + profile.benchmark()).toUpperCase();
        if (joined.contains("CSI300") || joined.contains("HS300")) {
            return "sh000300";
        }
        if (joined.contains("CSI500")) {
            return "sh000905";
        }
        if (joined.contains("CSI1000")) {
            return "sh000852";
        }
        if (joined.contains("STAR50")) {
            return "sh000688";
        }
        if (joined.contains("SSE50")) {
            return "sh000016";
        }
        if (joined.contains("CHINEXT")) {
            return "sz399006";
        }
        return null;
    }

    private String tencentIndexCodeFromAny(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\d{6}").matcher(value);
        while (matcher.find()) {
            String mapped = tencentIndexCode(matcher.group());
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    private String tencentIndexCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return switch (code.trim()) {
            case "000001" -> "sh000001";
            case "000016" -> "sh000016";
            case "000300", "399300" -> "sh000300";
            case "000688" -> "sh000688";
            case "000852" -> "sh000852";
            case "000905" -> "sh000905";
            case "000985" -> "sh000985";
            case "399001" -> "sz399001";
            case "399005" -> "sz399005";
            case "399006" -> "sz399006";
            default -> null;
        };
    }

    private TargetInstrument inferTargetEtf(FundProfile profile) {
        CacheEntry<TargetInstrument> cached = targetEtfCache.get(profile.fundCode());
        if (isFresh(cached, TARGET_ETF_CACHE_TTL)) {
            return cached.value();
        }
        for (String keyword : targetEtfKeywords(profile)) {
            TargetInstrument target = searchTargetEtf(keyword, profile.fundCode());
            if (target != null) {
                targetEtfCache.put(profile.fundCode(), new CacheEntry<>(target, LocalDateTime.now()));
                return target;
            }
        }
        targetEtfCache.put(profile.fundCode(), new CacheEntry<>(null, LocalDateTime.now()));
        return null;
    }

    private List<String> targetEtfKeywords(FundProfile profile) {
        List<String> keywords = new ArrayList<>();
        String name = firstNonBlank(profile.fundName(), profile.fundShortName());
        String company = normalizeCompanyName(profile.companyName());
        String base = name
                .replaceAll("(?i)ETF鑱旀帴.*$", "ETF")
                .replaceAll("鑱旀帴.*$", "")
                .replaceAll("[A-Z]$", "")
                .replace("鍩洪噾", "")
                .replace("鎸囨暟", "")
                .trim();
        if (!base.isBlank()) {
            keywords.add(base);
            keywords.add(base + "ETF");
            String withoutCompany = company.isBlank() ? base : base.replace(company, "");
            if (!withoutCompany.equals(base) && !withoutCompany.isBlank()) {
                keywords.add(withoutCompany + company);
                keywords.add(company + withoutCompany);
                keywords.add(company + withoutCompany + "ETF");
                keywords.add(withoutCompany + "ETF" + company);
            }
        }
        String theme = base.replace("涓瘉", "").replace(company, "").replace("ETF", "").trim();
        if (!theme.isBlank()) {
            keywords.add(theme + "ETF" + company);
            keywords.add(company + theme + "ETF");
        }
        return keywords.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
    }

    private TargetInstrument searchTargetEtf(String keyword, String sourceFundCode) {
        try {
            JsonNode datas = objectMapper.readTree(fetchUtf8Text(EASTMONEY_FUND_SEARCH_URL + urlEncode(keyword))).path("Datas");
            if (!datas.isArray()) {
                return null;
            }
            for (JsonNode item : datas) {
                String code = normalizeSecurityCode(item.path("CODE").asText(null));
                String name = item.path("NAME").asText("");
                if (code == null || code.equals(sourceFundCode) || !name.toUpperCase().contains("ETF") || name.contains("鑱旀帴")) {
                    continue;
                }
                String tencentCode = tencentStockCode(null, code);
                if (tencentCode != null) {
                    return new TargetInstrument(code, tencentCode, name);
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right == null ? "" : right;
    }

    private String normalizeCompanyName(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("鍩洪噾", "").replace("绠＄悊", "").trim();
    }

    private boolean isFresh(CacheEntry<?> entry, Duration ttl) {
        return entry != null
                && entry.cachedAt() != null
                && Duration.between(entry.cachedAt(), LocalDateTime.now()).compareTo(ttl) < 0;
    }

    private String marketFromEastmoneyRow(String row, String securityCode) {
        Matcher matcher = EASTMONEY_MARKET_CODE_PATTERN.matcher(row == null ? "" : row);
        while (matcher.find()) {
            if (securityCode.equals(matcher.group(2))) {
                return "1".equals(matcher.group(1)) ? "SSE" : "SZSE";
            }
        }
        return tencentStockCode(null, securityCode) == null ? null : (tencentStockCode(null, securityCode).startsWith("sh") ? "SSE" : "SZSE");
    }

    private String normalizeSecurityCode(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\d{6}").matcher(value);
        return matcher.find() ? matcher.group() : null;
    }

    private Integer integer(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal latestOfficialNav(String fundCode) {
        List<BigDecimal> values = jdbcTemplate.query("""
                SELECT unit_nav
                FROM fund_nav_daily
                WHERE fund_code = :fundCode AND unit_nav IS NOT NULL
                ORDER BY nav_date DESC
                LIMIT 1
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> rs.getBigDecimal("unit_nav"));
        return values.isEmpty() ? null : values.get(0);
    }

    private LocalDate latestOfficialNavDate(String fundCode) {
        List<LocalDate> values = jdbcTemplate.query("""
                SELECT nav_date
                FROM fund_nav_daily
                WHERE fund_code = :fundCode AND unit_nav IS NOT NULL
                ORDER BY nav_date DESC
                LIMIT 1
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> {
            java.sql.Date date = rs.getDate("nav_date");
            return date == null ? null : date.toLocalDate();
        });
        return values.isEmpty() ? null : values.get(0);
    }

    private LocalDate latestEstimateTradingDay(String fundCode) {
        List<LocalDate> days = jdbcTemplate.query("""
                SELECT MAX(trading_day) AS trading_day
                FROM (
                  SELECT trading_day
                  FROM fund_estimate_minute
                  WHERE fund_code = :fundCode
                  UNION ALL
                  SELECT trading_day
                  FROM fund_realtime_snapshot
                  WHERE fund_code = :fundCode
                ) t
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> {
            java.sql.Date date = rs.getDate("trading_day");
            return date == null ? null : date.toLocalDate();
        });
        return days.isEmpty() ? null : days.get(0);
    }

    private LocalDate latestClosedTradingDay() {
        LocalDateTime now = LocalDateTime.now(CHINA_ZONE);
        LocalDate date = now.toLocalDate();
        if (!isClosedForTradingDay(date)) {
            date = date.minusDays(1);
        }
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }
        return date;
    }

    private boolean hasClosedSnapshot(String fundCode, LocalDate tradingDay) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM fund_realtime_snapshot
                WHERE fund_code = :fundCode
                  AND trading_day = :tradingDay
                  AND data_status = 'CLOSED'
                """, Map.of("fundCode", fundCode, "tradingDay", tradingDay), Integer.class);
        return count != null && count > 0;
    }

    private CloseNav closeNav(String fundCode, LocalDate tradingDay) {
        List<CloseNav> values = jdbcTemplate.query("""
                SELECT unit_nav, daily_return_pct
                FROM fund_nav_daily
                WHERE fund_code = :fundCode AND nav_date = :tradingDay
                LIMIT 1
                """, Map.of("fundCode", fundCode, "tradingDay", tradingDay), (rs, rowNum) ->
                new CloseNav(rs.getBigDecimal("unit_nav"), rs.getBigDecimal("daily_return_pct")));
        return values.isEmpty() ? null : values.get(0);
    }

    private BigDecimal previousNav(String fundCode, LocalDate tradingDay) {
        List<BigDecimal> values = jdbcTemplate.query("""
                SELECT unit_nav
                FROM fund_nav_daily
                WHERE fund_code = :fundCode
                  AND nav_date < :tradingDay
                  AND unit_nav IS NOT NULL
                ORDER BY nav_date DESC
                LIMIT 1
                """, Map.of("fundCode", fundCode, "tradingDay", tradingDay), (rs, rowNum) -> rs.getBigDecimal("unit_nav"));
        return values.isEmpty() ? null : values.get(0);
    }

    private void persistFundMinute(
            String fundCode,
            LocalDate tradingDay,
            FundDtos.EstimateMinutePointResponse point,
            int sourceId
    ) {
        jdbcTemplate.update("""
                INSERT INTO fund_estimate_minute (
                  fund_code, trading_day, quote_time, estimate_nav, estimate_return_pct,
                  estimate_change_amount, official_last_nav, official_nav_date,
                  confidence_score, data_lag_seconds, source_id
                ) VALUES (
                  :fundCode, :tradingDay, :quoteTime, :estimateNav, :estimateReturnPct,
                  :estimateChangeAmount, :officialLastNav, :officialNavDate,
                  :confidenceScore, :dataLagSeconds, :sourceId
                )
                ON DUPLICATE KEY UPDATE
                  estimate_nav = VALUES(estimate_nav),
                  estimate_return_pct = VALUES(estimate_return_pct),
                  estimate_change_amount = VALUES(estimate_change_amount),
                  official_last_nav = VALUES(official_last_nav),
                  official_nav_date = VALUES(official_nav_date),
                  confidence_score = VALUES(confidence_score),
                  data_lag_seconds = VALUES(data_lag_seconds),
                  source_id = VALUES(source_id)
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("tradingDay", tradingDay)
                .addValue("quoteTime", point.quoteTime())
                .addValue("estimateNav", point.estimateNav())
                .addValue("estimateReturnPct", point.estimateReturnPct())
                .addValue("estimateChangeAmount", point.estimateChangeAmount())
                .addValue("officialLastNav", point.officialLastNav())
                .addValue("officialNavDate", point.officialNavDate())
                .addValue("confidenceScore", point.confidenceScore())
                .addValue("dataLagSeconds", point.dataLagSeconds())
                .addValue("sourceId", sourceId));
    }

    private List<NavRecord> parseNetWorthTrend(String body, LocalDate startDate, LocalDate endDate) {
        List<NavRecord> records = new ArrayList<>();
        Matcher matcher = NET_WORTH_TREND_PATTERN.matcher(body == null ? "" : body);
        if (!matcher.find()) {
            return records;
        }
        try {
            JsonNode nodes = objectMapper.readTree(matcher.group(1));
            if (!nodes.isArray()) {
                return records;
            }
            for (JsonNode node : nodes) {
                LocalDate navDate = Instant.ofEpochMilli(node.path("x").asLong())
                        .atZone(CHINA_ZONE)
                        .toLocalDate();
                if (navDate.isBefore(startDate) || navDate.isAfter(endDate)) {
                    continue;
                }
                BigDecimal unitNav = decimal(node.path("y").asText(null));
                BigDecimal dailyReturnPct = decimal(node.path("equityReturn").asText(null));
                if (unitNav != null) {
                    records.add(new NavRecord(navDate, unitNav, null, dailyReturnPct));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return records;
    }

    private List<NavRecord> parseNavRows(String body) {
        List<NavRecord> records = new ArrayList<>();
        Matcher rowMatcher = ROW_PATTERN.matcher(body);
        while (rowMatcher.find()) {
            Matcher cellMatcher = CELL_PATTERN.matcher(rowMatcher.group(1));
            List<String> cells = new ArrayList<>();
            while (cellMatcher.find()) {
                cells.add(stripTags(cellMatcher.group(1)));
            }
                if (cells.size() < 4 || "\u51c0\u503c\u65e5\u671f".equals(cells.get(0))) {
                continue;
            }
            LocalDate navDate = parseDate(cells.get(0));
            BigDecimal unitNav = decimal(cells.get(1));
            BigDecimal accumulatedNav = decimal(cells.get(2));
            BigDecimal dailyReturnPct = decimal(cells.get(3).replace("%", ""));
            if (navDate != null && unitNav != null) {
                records.add(new NavRecord(navDate, unitNav, accumulatedNav, dailyReturnPct));
            }
        }
        return records;
    }

    private int ensureDataSource(String sourceCode, String sourceName, String baseUrl, String remark) {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = :sourceCode LIMIT 1
                """, Map.of("sourceCode", sourceCode), (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  :sourceCode, :sourceName, 'THIRD_PARTY', 2, 'UNKNOWN', :baseUrl, 31, 1, :remark
                )
                """, new MapSqlParameterSource()
                .addValue("sourceCode", sourceCode)
                .addValue("sourceName", sourceName)
                .addValue("baseUrl", baseUrl)
                .addValue("remark", remark), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private String fetchUtf8Text(String url) {
        byte[] bytes = restClient.get().uri(url).retrieve().body(byte[].class);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private String fetchGbkText(String url) {
        byte[] bytes = restClient.get().uri(url).retrieve().body(byte[].class);
        return bytes == null ? "" : new String(bytes, GBK);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDateTime parseEstimateTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now(CHINA_ZONE);
        }
        try {
            return LocalDateTime.parse(value.trim(), ESTIMATE_TIME_FORMATTER);
        } catch (Exception ignored) {
            return LocalDateTime.now(CHINA_ZONE);
        }
    }

    private LocalDateTime normalizeMinuteQuoteTime(LocalDateTime quoteTime) {
        if (quoteTime == null) {
            return LocalDateTime.now(CHINA_ZONE);
        }
        LocalTime time = quoteTime.toLocalTime();
        if (time.isAfter(LocalTime.of(15, 0))) {
            return quoteTime.toLocalDate().atTime(15, 0);
        }
        if (time.isAfter(LocalTime.of(11, 30)) && time.isBefore(LocalTime.of(13, 0))) {
            return quoteTime.toLocalDate().atTime(11, 30);
        }
        return quoteTime;
    }

    private LocalDateTime displayQuoteTime(LocalDateTime sourceQuoteTime) {
        LocalDateTime now = LocalDateTime.now(CHINA_ZONE);
        if (sourceQuoteTime == null) {
            return now.truncatedTo(ChronoUnit.MINUTES);
        }
        if (isTradingMinute(now)
                && sourceQuoteTime.toLocalDate().equals(now.toLocalDate())
                && !sourceQuoteTime.isAfter(now)
                && Duration.between(sourceQuoteTime, now).abs().compareTo(Duration.ofMinutes(20)) <= 0) {
            return now.truncatedTo(ChronoUnit.MINUTES);
        }
        return sourceQuoteTime.truncatedTo(ChronoUnit.MINUTES);
    }

    private boolean isStaleForCurrentTradingMinute(LocalDateTime sourceQuoteTime, LocalDateTime now) {
        if (!isTradingMinute(now)) {
            return false;
        }
        if (sourceQuoteTime == null || !sourceQuoteTime.toLocalDate().equals(now.toLocalDate())) {
            return true;
        }
        return Duration.between(sourceQuoteTime, now).abs().compareTo(Duration.ofMinutes(20)) > 0;
    }

    private String resolveRealtimeDataStatus(
            LocalDateTime sourceQuoteTime,
            LocalDateTime now,
            BigDecimal estimateNav,
            BigDecimal estimateReturnPct
    ) {
        if (estimateNav == null && estimateReturnPct == null) {
            return "ERROR";
        }
        if (!isTradingMinute(now)) {
            return "NORMAL";
        }
        if (sourceQuoteTime == null || !sourceQuoteTime.toLocalDate().equals(now.toLocalDate())) {
            return "ERROR";
        }
        long lagSeconds = Duration.between(sourceQuoteTime, now).abs().getSeconds();
        if (lagSeconds >= errorLagThresholdSeconds) {
            return "ERROR";
        }
        if (lagSeconds >= delayedLagThresholdSeconds) {
            return "DELAYED";
        }
        return "NORMAL";
    }

    private void upsertRealtimeSnapshotStatusOnly(
            String fundCode,
            LocalDate tradingDay,
            LocalDateTime quoteTime,
            String dataStatus,
            int dataLagSeconds
    ) {
        jdbcTemplate.update("""
                INSERT INTO fund_realtime_snapshot (
                  fund_code, trading_day, quote_time, data_status, data_lag_seconds
                ) VALUES (
                  :fundCode, :tradingDay, :quoteTime, :dataStatus, :dataLagSeconds
                )
                ON DUPLICATE KEY UPDATE
                  trading_day = VALUES(trading_day),
                  quote_time = VALUES(quote_time),
                  data_status = VALUES(data_status),
                  data_lag_seconds = VALUES(data_lag_seconds),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("tradingDay", tradingDay)
                .addValue("quoteTime", quoteTime)
                .addValue("dataStatus", dataStatus)
                .addValue("dataLagSeconds", dataLagSeconds));
    }

    private boolean isOpeningBaselineMinute(LocalDateTime quoteTime) {
        if (quoteTime == null || !isTradingMinute(quoteTime)) {
            return false;
        }
        LocalTime time = quoteTime.toLocalTime();
        return !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(9, 35));
    }

    private boolean isTradingMinute(LocalDateTime time) {
        DayOfWeek dayOfWeek = time.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime localTime = time.toLocalTime();
        return (!localTime.isBefore(LocalTime.of(9, 30)) && !localTime.isAfter(LocalTime.of(11, 30)))
                || (!localTime.isBefore(LocalTime.of(13, 0)) && !localTime.isAfter(LocalTime.of(15, 0)));
    }

    private boolean isClosedForTradingDay(LocalDate tradingDay) {
        LocalDateTime now = LocalDateTime.now(CHINA_ZONE);
        if (tradingDay.isBefore(now.toLocalDate())) {
            return true;
        }
        return tradingDay.equals(now.toLocalDate()) && !now.toLocalTime().isBefore(LocalTime.of(15, 0));
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank() || "--".equals(value.trim())) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimalLoose(String value) {
        if (value == null) {
            return null;
        }
        return decimal(value.replace(",", "").replace("%", "").trim());
    }

    private String stripTags(String value) {
        return value.replaceAll("<[^>]+>", "").replace("&nbsp;", "").trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class RefreshThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "fund-refresh-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private record NavRecord(
            LocalDate navDate,
            BigDecimal unitNav,
            BigDecimal accumulatedNav,
            BigDecimal dailyReturnPct
    ) {
    }

    private record PositionWeight(String tencentCode, BigDecimal navRatio) {
    }

    private record HoldingEstimate(
            BigDecimal estimateNav,
            BigDecimal estimateReturnPct,
            BigDecimal confidenceScore
    ) {
    }

    private record FundExposure(
            BigDecimal stockPct,
            BigDecimal bondPct,
            BigDecimal cashPct
    ) {
        private static FundExposure empty() {
            return new FundExposure(null, null, null);
        }
    }

    private record FundProfile(
            String fundCode,
            String fundName,
            String fundShortName,
            String companyName,
            String benchmark,
            String trackingIndexCode,
            Boolean indexFund
    ) {
    }

    private record TargetInstrument(
            String code,
            String tencentCode,
            String name
    ) {
    }

    private record CacheEntry<T>(
            T value,
            LocalDateTime cachedAt
    ) {
    }

    private record CloseNav(
            BigDecimal unitNav,
            BigDecimal dailyReturnPct
    ) {
    }

    private record CloseEstimateGap(
            BigDecimal estimateReturnPct,
            BigDecimal officialReturnPct
    ) {
    }
}

