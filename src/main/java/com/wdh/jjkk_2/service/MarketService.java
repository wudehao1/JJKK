package com.wdh.jjkk_2.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.dto.MarketDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大盘首页行情、分时图和日线历史的数据服务。
 *
 * 各市场公开接口差异很大：A 股常用东方财富和腾讯，港股/美股/黄金需要新浪或 Yahoo
 * 做补充，部分历史数据还要回退到本地 MySQL/Redis。这里把复杂的数据源优先级和兜底
 * 链路全部隐藏起来，前端只面对稳定的 overview、minute、history 三类结果。
 */
@Service
public class MarketService {
    /*
     * 首页默认展示的大盘品种。
     *
     * 这里的顺序就是 /market/overview 返回顺序，也是小程序首页分组渲染顺序。
     * 新增市场时要同时确认 symbol、腾讯代码、东方财富 secid、交易时段和兜底源是否可用。
     */
    private static final List<DefaultIndex> DEFAULT_INDICES = List.of(
            new DefaultIndex("A股", "SSE", "000001", "上证指数", "sh000001", "1.000001"),
            new DefaultIndex("A股", "SZSE", "399001", "深证成指", "sz399001", "0.399001"),
            new DefaultIndex("A股", "SZSE", "399006", "创业板指", "sz399006", "0.399006"),
            new DefaultIndex("A股", "SSE", "000300", "沪深300", "sh000300", "1.000300"),
            new DefaultIndex("A股", "SSE", "000905", "中证500", "sh000905", "1.000905"),
            new DefaultIndex("A股", "SSE", "000688", "科创50", "sh000688", "1.000688"),
            new DefaultIndex("港股", "HKEX", "HSI", "恒生指数", "hkHSI", "100.HSI"),
            new DefaultIndex("港股", "HKEX", "HSCEI", "恒生国企", "hkHSCEI", "100.HSCEI"),
            new DefaultIndex("港股", "HKEX", "HSTECH", "恒生科技", "hkHSTECH", "124.HSTECH"),
            new DefaultIndex("美股", "NYSE", "DJIA", "道琼斯", null, "100.DJIA"),
            new DefaultIndex("美股", "NYSE", "SPX", "标普500", null, "100.SPX"),
            new DefaultIndex("美股", "NASDAQ", "NDX100", "纳斯达克100", null, "100.NDX100"),
            new DefaultIndex("黄金", "OTHER", "GC", "纽约黄金", null, null),
            new DefaultIndex("黄金", "OTHER", "AU9999", "上金Au99.99", null, null),
            new DefaultIndex("黄金", "FX", "UDI", "美元指数", null, "100.UDI")
    ).stream()
            .filter(index -> !"UDI".equals(index.symbol()))
            .toList();
    private static final Pattern TENCENT_QUOTE_PATTERN = Pattern.compile("v_(sh|sz)(\\d{6})=\"([^\"]*)\";");
    private static final Pattern SINA_QUOTE_PATTERN = Pattern.compile("var\\s+hq_str_([^=]+)=\"([^\"]*)\";");
    private static final DateTimeFormatter TENCENT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter SINA_SLASH_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter SINA_COMPACT_TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss");
    private static final Charset GBK = Charset.forName("GBK");
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String TENCENT_INDEX_QUOTE_URL = "http://qt.gtimg.cn/q=" + DEFAULT_INDICES.stream()
            .map(DefaultIndex::tencentCode)
            .filter(code -> code != null && !code.isBlank())
            .reduce((left, right) -> left + "," + right)
            .orElse("sh000001");
    private static final String TENCENT_INDEX_KLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
    private static final String EASTMONEY_TRENDS_URL = "https://push2his.eastmoney.com/api/qt/stock/trends2/get";
    private static final String EASTMONEY_KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";
    private static final String EASTMONEY_INDEX_QUOTE_URL = "https://push2.eastmoney.com/api/qt/ulist.np/get";
    private static final String EASTMONEY_SECTOR_RANKING_URL = "https://push2.eastmoney.com/api/qt/clist/get";
    private static final String EASTMONEY_FUND_RANKING_URL = "http://fund.eastmoney.com/data/rankhandler.aspx";
    private static final String SINA_REFERER = "https://finance.sina.com.cn/";
    private static final String SINA_QUOTE_URL = "https://hq.sinajs.cn/list=";
    private static final String SINA_HK_MINUTE_URL = "https://stock.finance.sina.com.cn/hkstock/api/openapi.php/HK_StockService.getHKMinline";
    private static final String SINA_US_DAILY_URL = "https://stock.finance.sina.com.cn/usstock/api/jsonp.php/var%20d=/US_MinKService.getDailyK";
    private static final String SINA_FUTURES_DAILY_URL = "https://stock2.finance.sina.com.cn/futures/api/json.php/IndexService.getInnerFuturesDailyKLine";
    private static final String SINA_FUTURES_MINUTE_URL = "https://stock2.finance.sina.com.cn/futures/api/json.php/IndexService.getInnerFuturesMiniKLine5m";
    private static final String SINA_GLOBAL_FUTURES_DAILY_URL = "https://stock2.finance.sina.com.cn/futures/api/json.php/GlobalFuturesService.getGlobalFuturesDailyKLine";
    private static final String SINA_GLOBAL_FUTURES_MINUTE_URL = "https://stock2.finance.sina.com.cn/futures/api/json.php/GlobalFuturesService.getGlobalFuturesMinLine";
    private static final String EASTMONEY_FUTURES_API_URL = "https://futsseapi.eastmoney.com/";
    private static final String EASTMONEY_FUTURES_TOKEN = "1101ffec61617c99be287c1bec3085ff";
    private static final String EASTMONEY_FUTURES_FIELDS = "name,dm,sc,p,zsjd,zdf,o,h,l,vol,cje,zde,zjsj,tjd,utime,uid,zf,zt,dt";
    private static final String SHFE_DAILY_KX_URL = "https://www.shfe.cn/data/tradedata/future/dailydata/kx%s.dat";
    private static final String SGE_DELAYED_QUOTE_URL = "https://www.sge.com.cn/sjzx/yshqbg";
    private static final String SGE_DAILY_QUOTE_URL = "https://www.sge.com.cn/sjzx/quotation_daily_new?start_date=%s&end_date=%s&inst_ids=Au99.99";
    private static final String YAHOO_CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final Pattern SGE_REPORT_DATE_PATTERN = Pattern.compile("(\\d{4})年(\\d{2})月(\\d{2})日");
    private static final Pattern SGE_DELAYED_AU9999_PATTERN = Pattern.compile(
            "Au99\\.99\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)"
    );
    private static final Pattern SGE_DAILY_AU9999_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s+Au99\\.99\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)%?\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)\\s+(-?[\\d,]+(?:\\.\\d+)?|--?)"
    );
    // The official page is limited to 20 rows. A 20-calendar-day Au99.99-only chunk stays below that limit.
    private static final int SGE_DAILY_CHUNK_DAYS = 20;
    private static final Duration FUND_RANK_CACHE_TTL = Duration.ofMinutes(3);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId US_EASTERN_ZONE = ZoneId.of("America/New_York");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final IntradayRedisCacheService intradayRedisCacheService;
    private volatile CachedFundRankSnapshot cachedFundRankSnapshot;

    public MarketService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            IntradayRedisCacheService intradayRedisCacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.intradayRedisCacheService = intradayRedisCacheService;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(6000);
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                .defaultHeader("Referer", "https://quote.eastmoney.com/")
                .build();
    }

    /**
     * 返回大盘首页行情。
     *
     * 优先合并实时公开源行情，拿不到时读取本地最近一次快照；某个品种仍然缺失时返回空
     * quote DTO，前端可以显示“暂无数据”，但六宫格/多市场布局不会因为缺一项而整体错位。
     */
    public MarketDtos.OverviewResponse overview() {
        List<MarketDtos.IndexQuoteResponse> liveQuotes = enrichOverviewQuotes(mergeDefaultQuotes(
                fetchOfficialGoldQuotes(),
                mergeDefaultQuotes(
                        fetchEastmoneyIndexQuotes(),
                        mergeDefaultQuotes(fetchEastmoneyFutureQuotes(), mergeDefaultQuotes(fetchSinaIndexQuotes(), fetchTencentIndexQuotes()))
                )
        ));
        if (hasAnyQuote(liveQuotes)) {
            return overviewFromQuotes(liveQuotes);
        }

        List<MarketDtos.IndexQuoteResponse> quotes = enrichOverviewQuotes(mergeDefaultQuotes(loadStoredIndexQuotes(), List.of()));
        if (!hasAnyQuote(quotes)) {
            return new MarketDtos.OverviewResponse(null, LocalDateTime.now(CHINA_ZONE), "NO_DATA", quotes);
        }

        LocalDate tradingDay = latestTradingDay();
        LocalDateTime updatedAt = latestQuoteTime();
        String status = isCurrentTradingData(quotes) ? "NORMAL" : "STALE";
        return new MarketDtos.OverviewResponse(tradingDay, updatedAt, status, quotes);
    }

    private List<MarketDtos.IndexQuoteResponse> enrichOverviewQuotes(List<MarketDtos.IndexQuoteResponse> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return List.of();
        }
        List<MarketDtos.IndexQuoteResponse> enriched = new ArrayList<>();
        for (MarketDtos.IndexQuoteResponse quote : quotes) {
            DefaultIndex index = defaultIndex(quote.symbol());
            MarketDtos.MinutePointResponse minutePoint = shouldCheckMinuteQuoteForOverview(index)
                    ? latestMinutePointForOverview(index)
                    : null;
            enriched.add(shouldUseMinuteQuoteForOverview(index, quote, minutePoint)
                    ? overviewQuoteFromMinute(quote, minutePoint)
                    : quote);
        }
        return sortDefaultIndices(enriched);
    }

    private boolean shouldCheckMinuteQuoteForOverview(DefaultIndex index) {
        return index != null && !isOfficialGoldMarket(index);
    }

    private boolean shouldUseMinuteQuoteForOverview(DefaultIndex index,
                                                    MarketDtos.IndexQuoteResponse quote,
                                                    MarketDtos.MinutePointResponse minutePoint) {
        if (index == null || quote == null || minutePoint == null || minutePoint.price() == null) {
            return false;
        }
        if (isFutureQuoteTime(quote.quoteTime())) {
            return true;
        }
        if (quote.quoteTime() == null || (minutePoint.quoteTime() != null && minutePoint.quoteTime().isAfter(quote.quoteTime()))) {
            return true;
        }
        if (minutePoint.changePct() == null) {
            return false;
        }
        BigDecimal quotePct = quote.changePct();
        BigDecimal minutePct = minutePoint.changePct();
        boolean quoteLooksFlat = quotePct == null || quotePct.abs().compareTo(BigDecimal.valueOf(0.01)) < 0;
        boolean minuteHasSignal = minutePct.abs().compareTo(BigDecimal.valueOf(0.01)) >= 0;
        return quoteLooksFlat && minuteHasSignal;
    }

    private MarketDtos.IndexQuoteResponse overviewQuoteFromMinute(MarketDtos.IndexQuoteResponse quote,
                                                                  MarketDtos.MinutePointResponse minutePoint) {
        BigDecimal changeAmount = changeAmountFromPct(minutePoint.price(), minutePoint.changePct());
        return new MarketDtos.IndexQuoteResponse(
                quote.market(),
                quote.symbol(),
                quote.name(),
                minutePoint.price(),
                changeAmount,
                minutePoint.changePct(),
                quote.turnover(),
                dataLagSeconds(minutePoint.quoteTime()),
                minutePoint.quoteTime()
        );
    }

    private BigDecimal changeAmountFromPct(BigDecimal price, BigDecimal changePct) {
        if (price == null || changePct == null) {
            return null;
        }
        BigDecimal denominator = BigDecimal.valueOf(100).add(changePct);
        if (BigDecimal.ZERO.compareTo(denominator) == 0) {
            return null;
        }
        BigDecimal previous = price.multiply(BigDecimal.valueOf(100)).divide(denominator, 6, RoundingMode.HALF_UP);
        return price.subtract(previous);
    }

    private MarketDtos.MinutePointResponse latestMinutePointForOverview(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        List<InstrumentRef> instruments = findIndexInstruments(index.symbol());
        if (instruments.isEmpty()) {
            return null;
        }
        long instrumentId = instruments.get(0).id();
        LocalDate expectedDay = expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
        List<MarketDtos.MinutePointResponse> points = loadMinutePoints(instrumentId, expectedDay, index);
        if (points.isEmpty()) {
            LocalDate tradingDay = latestMinuteTradingDayOnOrBefore(instrumentId, expectedDay);
            if (tradingDay != null) {
                points = loadMinutePoints(instrumentId, tradingDay, index);
            }
        }
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }

    private MarketDtos.OverviewResponse overviewFromQuotes(List<MarketDtos.IndexQuoteResponse> quotes) {
        LocalDate tradingDay = quotes.stream()
                .map(MarketDtos.IndexQuoteResponse::quoteTime)
                .filter(time -> time != null)
                .map(LocalDateTime::toLocalDate)
                .findFirst()
                .orElse(LocalDate.now(CHINA_ZONE));
        LocalDateTime updatedAt = quotes.stream()
                .map(MarketDtos.IndexQuoteResponse::quoteTime)
                .filter(time -> time != null)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now(CHINA_ZONE));
        return new MarketDtos.OverviewResponse(tradingDay, updatedAt, "NORMAL", quotes);
    }

    private List<MarketDtos.IndexQuoteResponse> mergeDefaultQuotes(List<MarketDtos.IndexQuoteResponse> primary,
                                                                   List<MarketDtos.IndexQuoteResponse> fallback) {
        Map<String, MarketDtos.IndexQuoteResponse> bySymbol = new LinkedHashMap<>();
        for (MarketDtos.IndexQuoteResponse quote : fallback == null ? List.<MarketDtos.IndexQuoteResponse>of() : fallback) {
            if (quote != null && quote.symbol() != null) {
                bySymbol.put(quote.symbol(), quote);
            }
        }
        for (MarketDtos.IndexQuoteResponse quote : primary == null ? List.<MarketDtos.IndexQuoteResponse>of() : primary) {
            if (quote == null || quote.symbol() == null) {
                continue;
            }
            MarketDtos.IndexQuoteResponse existing = bySymbol.get(quote.symbol());
            if (existing == null || isSameOrNewer(quote.quoteTime(), existing.quoteTime())) {
                bySymbol.put(quote.symbol(), quote);
            }
        }
        return DEFAULT_INDICES.stream()
                .filter(item -> !"UDI".equals(item.symbol()))
                .map(item -> {
                    MarketDtos.IndexQuoteResponse quote = bySymbol.get(item.symbol());
                    return quote == null ? emptyIndexQuote(item) : quote;
                })
                .toList();
    }

    private MarketDtos.IndexQuoteResponse emptyIndexQuote(DefaultIndex item) {
        return new MarketDtos.IndexQuoteResponse(
                item.market(),
                item.symbol(),
                item.name(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private boolean hasAnyQuote(List<MarketDtos.IndexQuoteResponse> quotes) {
        return quotes != null && quotes.stream().anyMatch(item ->
                item != null && (item.lastPrice() != null || item.quoteTime() != null));
    }

    /**
     * 根据本地基金估算快照统计上涨、下跌和平盘数量。
     *
     * 这个接口用于市场广度条或涨跌对比组件。如果前端当前版本隐藏该模块，后端仍保留
     * 能力，后续需要恢复基金广度时不必重新设计数据口径。
     */
    public MarketDtos.FundBreadthResponse fundBreadth() {
        FundRankSnapshot liveSnapshot = fetchEastmoneyFundRankSnapshot();
        if (liveSnapshot != null && liveSnapshot.breadth().totalCount() > 0) {
            return liveSnapshot.breadth();
        }
        List<MarketDtos.FundBreadthResponse> rows = jdbcTemplate.query("""
                SELECT
                  MAX(nav.nav_date) AS trading_day,
                  MAX(COALESCE(snap.quote_time, CAST(nav.nav_date AS DATETIME))) AS updated_at,
                  SUM(CASE WHEN COALESCE(snap.estimate_return_pct, nav.daily_return_pct) > 0 THEN 1 ELSE 0 END) AS up_count,
                  SUM(CASE WHEN COALESCE(snap.estimate_return_pct, nav.daily_return_pct) < 0 THEN 1 ELSE 0 END) AS down_count,
                  SUM(CASE
                        WHEN COALESCE(snap.estimate_return_pct, nav.daily_return_pct) = 0
                          OR COALESCE(snap.estimate_return_pct, nav.daily_return_pct) IS NULL
                        THEN 1 ELSE 0
                      END) AS flat_count,
                  COUNT(*) AS total_count
                FROM fund_share_class fsc
                LEFT JOIN (
                  SELECT n1.fund_code, n1.nav_date, n1.unit_nav, n1.daily_return_pct
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = fsc.fund_code
                LEFT JOIN (
                  SELECT s1.fund_code, s1.trading_day, s1.quote_time, s1.estimate_return_pct
                  FROM fund_realtime_snapshot s1
                  JOIN (
                    SELECT fund_code, MAX(trading_day) AS trading_day
                    FROM fund_realtime_snapshot
                    GROUP BY fund_code
                  ) s2 ON s2.fund_code = s1.fund_code AND s2.trading_day = s1.trading_day
                ) snap ON snap.fund_code = fsc.fund_code
                WHERE fsc.status <> 'TERMINATED'
                """, (rs, rowNum) -> {
            int upCount = rs.getInt("up_count");
            int downCount = rs.getInt("down_count");
            int flatCount = rs.getInt("flat_count");
            int totalCount = rs.getInt("total_count");
            return new MarketDtos.FundBreadthResponse(
                    localDate(rs.getDate("trading_day")),
                    timestamp(rs.getTimestamp("updated_at")),
                    upCount,
                    downCount,
                    flatCount,
                    totalCount,
                    ratioCount(upCount, totalCount),
                    ratioCount(downCount, totalCount)
            );
        });
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return new MarketDtos.FundBreadthResponse(
                null,
                LocalDateTime.now(CHINA_ZONE),
                0,
                0,
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    public List<MarketDtos.FundRankingResponse> fundRankings(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        FundRankSnapshot liveSnapshot = fetchEastmoneyFundRankSnapshot();
        if (liveSnapshot != null && !liveSnapshot.rankings().isEmpty()) {
            return liveSnapshot.rankings().stream()
                    .limit(safeLimit)
                    .toList();
        }
        return jdbcTemplate.query("""
                SELECT
                  fsc.fund_code,
                  fsc.fund_name,
                  sector.sector_name,
                  nav.nav_date,
                  nav.unit_nav,
                  COALESCE(snap.estimate_return_pct, nav.daily_return_pct) AS return_pct,
                  CASE WHEN snap.estimate_return_pct IS NOT NULL THEN 'ESTIMATE' ELSE 'OFFICIAL' END AS data_type
                FROM fund_share_class fsc
                LEFT JOIN (
                  SELECT
                    fsm.fund_code,
                    GROUP_CONCAT(fs.sector_name ORDER BY fs.sector_type, fs.sector_name SEPARATOR ' / ') AS sector_name
                  FROM fund_sector_member fsm
                  JOIN fund_sector fs ON fs.id = fsm.sector_id
                  WHERE fs.enabled = 1
                    AND (fsm.effective_to IS NULL OR fsm.effective_to >= CURRENT_DATE)
                  GROUP BY fsm.fund_code
                ) sector ON sector.fund_code = fsc.fund_code
                LEFT JOIN (
                  SELECT n1.fund_code, n1.nav_date, n1.unit_nav, n1.daily_return_pct
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = fsc.fund_code
                LEFT JOIN (
                  SELECT s1.fund_code, s1.estimate_return_pct
                  FROM fund_realtime_snapshot s1
                  JOIN (
                    SELECT fund_code, MAX(trading_day) AS trading_day
                    FROM fund_realtime_snapshot
                    GROUP BY fund_code
                  ) s2 ON s2.fund_code = s1.fund_code AND s2.trading_day = s1.trading_day
                ) snap ON snap.fund_code = fsc.fund_code
                WHERE fsc.status <> 'TERMINATED'
                  AND COALESCE(snap.estimate_return_pct, nav.daily_return_pct) IS NOT NULL
                ORDER BY COALESCE(snap.estimate_return_pct, nav.daily_return_pct) DESC, fsc.fund_code
                LIMIT :limit
                """, Map.of("limit", safeLimit), (rs, rowNum) -> new MarketDtos.FundRankingResponse(
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("sector_name"),
                localDate(rs.getDate("nav_date")),
                rs.getBigDecimal("unit_nav"),
                rs.getBigDecimal("return_pct"),
                rs.getString("data_type")
        ));
    }

    public List<MarketDtos.SectorRankingResponse> sectorRankings(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 30);
        return fetchEastmoneySectorRankings(safeLimit);
    }

    /**
     * 返回单个大盘品种的当日分时线。
     *
     * 交易时段内会先刷新选中品种，尽量拿到最近一分钟；非交易时段则展示最近一个有数据的
     * 交易日，避免晚上、周末或节假日点进详情页时图表为空。返回点位按市场交易时间对齐，
     * 前端按分钟折线绘制即可。
     */
    public MarketDtos.MinuteSeriesResponse minuteSeries(String symbol) {
        DefaultIndex fallback = defaultIndex(symbol);
        if (fallback != null && isOfficialGoldMarket(fallback)) {
            return officialGoldMinuteSeries(fallback);
        }
        if (fallback != null) {
            refreshIndexIntraday(symbol);
        } else {
            fetchTencentIndexQuotes();
        }

        List<InstrumentRef> instruments = findIndexInstruments(symbol);
        if (instruments.isEmpty() && fallback != null) {
            fetchEastmoneyIndexQuotes();
            fetchTencentIndexQuotes();
            fetchSinaIndexQuotes();
            instruments = findIndexInstruments(symbol);
        }

        if (instruments.isEmpty()) {
            String name = fallback == null ? symbol : fallback.name();
            String market = fallback == null ? "INDEX" : fallback.market();
            return new MarketDtos.MinuteSeriesResponse(
                    market,
                    symbol,
                    name,
                    null,
                    marketDisplayTime(fallback, LocalDateTime.now(CHINA_ZONE)),
                    "NO_DATA",
                    List.of()
            );
        }

        InstrumentRef instrument = instruments.get(0);
        LocalDateTime now = LocalDateTime.now(CHINA_ZONE);
        LocalDate expectedDay = expectedTradingDay(now, fallback);
        if (fallback != null && isPreOpenClearWindow(now, fallback)) {
            return new MarketDtos.MinuteSeriesResponse(
                    instrument.market(),
                    instrument.symbol(),
                    instrument.name(),
                    expectedDay,
                    marketDisplayTime(fallback, now),
                    "NO_DATA",
                    List.of()
            );
        }

        LocalDate tradingDay = expectedDay;
        List<MarketDtos.MinutePointResponse> points = loadMinutePoints(instrument.id(), tradingDay, fallback);
        if (points.isEmpty() && !isHkMarket(fallback)) {
            points = sanitizeMinutePoints(intradayRedisCacheService.loadMarketMinutePoints(symbol, tradingDay), fallback, tradingDay);
        }
        if (points.isEmpty() && !isPreOpenClearWindow(now, fallback)) {
            fetchTencentIndexQuotes();
            fetchSinaIndexQuotes();
            points = loadMinutePoints(instrument.id(), tradingDay, fallback);
            if (points.isEmpty() && !isHkMarket(fallback)) {
                points = sanitizeMinutePoints(intradayRedisCacheService.loadMarketMinutePoints(symbol, tradingDay), fallback, tradingDay);
            }
        }

        if (points.isEmpty()) {
            LocalDate fallbackTradingDay = latestMinuteTradingDayOnOrBefore(instrument.id(), expectedDay);
            if (fallbackTradingDay != null) {
                tradingDay = fallbackTradingDay;
                points = loadMinutePoints(instrument.id(), tradingDay, fallback);
            }
        }

        if (points.isEmpty()) {
            points = loadLatestQuotePoint(instrument.id());
            if (!points.isEmpty() && points.get(0).quoteTime() != null) {
                tradingDay = marketTradingDay(fallback, points.get(0).quoteTime());
            }
        }

        if (points.isEmpty()) {
            return new MarketDtos.MinuteSeriesResponse(
                    instrument.market(),
                    instrument.symbol(),
                    instrument.name(),
                    expectedDay,
                    marketDisplayTime(fallback, now),
                    "NO_DATA",
                    List.of()
            );
        }

        LocalDateTime updatedAt = points.get(points.size() - 1).quoteTime();
        String dataStatus = minuteDataStatus(fallback, tradingDay, expectedDay, updatedAt, now);
        MarketDtos.MinuteSeriesResponse response = new MarketDtos.MinuteSeriesResponse(
                instrument.market(),
                instrument.symbol(),
                instrument.name(),
                tradingDay,
                updatedAt,
                dataStatus,
                points
        );
        intradayRedisCacheService.cacheMarketMinuteResponse(response);
        return marketDisplayMinuteResponse(fallback, response);
    }

    private String minuteDataStatus(DefaultIndex index,
                                    LocalDate tradingDay,
                                    LocalDate expectedDay,
                                    LocalDateTime updatedAt,
                                    LocalDateTime now) {
        if (tradingDay == null || expectedDay == null || !tradingDay.equals(expectedDay)) {
            return "STALE";
        }
        if (isUsMarket(index) && updatedAt != null) {
            LocalDateTime sessionStart = usSessionStartInChina(tradingDay);
            LocalDateTime sessionEnd = usSessionEndInChina(tradingDay);
            LocalDateTime expectedLatest = now.isBefore(sessionEnd) ? now : sessionEnd;
            if (expectedLatest.isAfter(sessionStart)
                    && updatedAt.isBefore(expectedLatest.minusMinutes(15))) {
                return "STALE";
            }
        }
        return "NORMAL";
    }

    private MarketDtos.MinuteSeriesResponse marketDisplayMinuteResponse(DefaultIndex index,
                                                                         MarketDtos.MinuteSeriesResponse response) {
        if (!isUsMarket(index) || response == null) {
            return response;
        }
        List<MarketDtos.MinutePointResponse> displayPoints = response.points().stream()
                .map(point -> new MarketDtos.MinutePointResponse(
                        marketDisplayTime(index, point.quoteTime()),
                        point.price(),
                        point.changePct(),
                        point.volume(),
                        point.turnover()
                ))
                .toList();
        return new MarketDtos.MinuteSeriesResponse(
                response.market(),
                response.symbol(),
                response.name(),
                response.tradingDay(),
                marketDisplayTime(index, response.updatedAt()),
                response.dataStatus(),
                displayPoints
        );
    }

    /**
     * 返回指定区间的大盘日线走势。
     *
     * 近 1 月、3 月、6 月、1 年优先读取公开日 K 接口；如果上游没有返回，再尝试用本地
     * 已保存的分钟数据聚合成日线。这样能保证长期趋势图尽量真实，同时具备离线兜底能力。
     */
    public MarketDtos.HistorySeriesResponse historySeries(String symbol, String range) {
        DateRange dateRange = dateRange(range);
        DefaultIndex fallback = defaultIndex(symbol);
        String market = fallback == null ? "INDEX" : fallback.market();
        String name = fallback == null ? symbol : fallback.name();
        List<MarketDtos.HistoryPointResponse> points;
        if (fallback != null && isGlobalGoldMarket(fallback)) {
            points = fetchSinaGlobalFutureDailyKline(symbol, dateRange.startDate(), dateRange.endDate());
        } else if (fallback != null && "AU9999".equals(fallback.symbol())) {
            points = fetchSgeSpotGoldDailyKline(symbol, dateRange.startDate(), dateRange.endDate());
        } else {
            points = fetchEastmoneyKline(symbol, dateRange.startDate(), dateRange.endDate());
        }
        if (fallback != null && isOfficialGoldMarket(fallback)) {
            points = mergeHistoryPoints(
                    loadStoredDailyHistory(symbol, dateRange.startDate(), dateRange.endDate()),
                    points
            );
            points = sanitizeHistoryPoints(fallback, points);
            return new MarketDtos.HistorySeriesResponse(
                    market,
                    symbol,
                    name,
                    dateRange.range(),
                    dateRange.startDate(),
                    dateRange.endDate(),
                    LocalDateTime.now(CHINA_ZONE),
                    historyDataStatus(fallback, points, dateRange),
                    points
            );
        }
        if (points.isEmpty() || shouldReplaceWithHistoryFallback(fallback, points, dateRange.endDate())) {
            List<MarketDtos.HistoryPointResponse> tencentPoints = fetchTencentKline(symbol, dateRange.startDate(), dateRange.endDate());
            if (!tencentPoints.isEmpty()) {
                points = tencentPoints;
            }
        }
        if (points.isEmpty() || shouldReplaceWithHistoryFallback(fallback, points, dateRange.endDate())) {
            List<MarketDtos.HistoryPointResponse> fallbackPoints = fetchSinaDailyKline(symbol, dateRange.startDate(), dateRange.endDate());
            if (fallbackPoints.isEmpty()) {
                fallbackPoints = fetchShfeGoldMainDailyKline(symbol, dateRange.startDate(), dateRange.endDate());
            }
            if (fallbackPoints.isEmpty()) {
                fallbackPoints = fetchSinaFutureDailyKline(symbol, dateRange.startDate(), dateRange.endDate());
            }
            if (fallbackPoints.isEmpty()) {
                fallbackPoints = fetchYahooDailyKline(symbol, dateRange.startDate(), dateRange.endDate());
            }
            if (!fallbackPoints.isEmpty()) {
                points = fallbackPoints;
            }
        }
        if (points.isEmpty()) {
            points = loadStoredDailyHistory(symbol, dateRange.startDate(), dateRange.endDate());
        }
        points = sanitizeHistoryPoints(fallback, points);
        return new MarketDtos.HistorySeriesResponse(
                market,
                symbol,
                name,
                dateRange.range(),
                dateRange.startDate(),
                dateRange.endDate(),
                LocalDateTime.now(CHINA_ZONE),
                historyDataStatus(fallback, points, dateRange),
                points
        );
    }

    private List<MarketDtos.HistoryPointResponse> mergeHistoryPoints(
            List<MarketDtos.HistoryPointResponse> fallbackPoints,
            List<MarketDtos.HistoryPointResponse> preferredPoints
    ) {
        Map<LocalDate, MarketDtos.HistoryPointResponse> byDate = new LinkedHashMap<>();
        if (fallbackPoints != null) {
            for (MarketDtos.HistoryPointResponse point : fallbackPoints) {
                if (point != null && point.tradingDay() != null) {
                    byDate.put(point.tradingDay(), point);
                }
            }
        }
        if (preferredPoints != null) {
            for (MarketDtos.HistoryPointResponse point : preferredPoints) {
                if (point != null && point.tradingDay() != null) {
                    byDate.put(point.tradingDay(), point);
                }
            }
        }
        return byDate.values().stream()
                .sorted((left, right) -> left.tradingDay().compareTo(right.tradingDay()))
                .toList();
    }

    private List<MarketDtos.HistoryPointResponse> sanitizeHistoryPoints(DefaultIndex index,
                                                                         List<MarketDtos.HistoryPointResponse> points) {
        if (points == null || points.isEmpty() || index == null) {
            return points == null ? List.of() : points;
        }
        LocalDateTime now = LocalDateTime.now(CHINA_ZONE);
        LocalDate maxCompletedDay = expectedTradingDay(now, index);
        if (isHkMarket(index)
                && maxCompletedDay.equals(now.toLocalDate())
                && now.toLocalTime().isBefore(LocalTime.of(16, 0))) {
            maxCompletedDay = previousWeekday(now.toLocalDate().minusDays(1));
        }
        LocalDate finalMaxCompletedDay = maxCompletedDay;
        return points.stream()
                .filter(point -> point != null && point.tradingDay() != null)
                .filter(point -> !point.tradingDay().isAfter(finalMaxCompletedDay))
                .sorted((left, right) -> left.tradingDay().compareTo(right.tradingDay()))
                .toList();
    }

    /**
     * 定时刷新首页配置的全部大盘品种分时数据。
     *
     * 调度器每分钟调用一次，服务内部会根据交易时间和品种类型决定是否真正抓取，
     * 避免港股、美股、黄金等不同时区市场全部用 A 股交易时间判断。
     */
    public void refreshDefaultIndexIntraday() {
        for (DefaultIndex item : DEFAULT_INDICES) {
            if ("UDI".equals(item.symbol())) {
                continue;
            }
            refreshIndexIntraday(item.symbol(), false);
        }
        if (isTradingTime(LocalDateTime.now(CHINA_ZONE))) {
            fetchTencentIndexQuotes();
        }
        fetchSinaIndexQuotes();
    }

    public void clearTodayMinuteQuotes() {
        jdbcTemplate.update("""
                DELETE FROM market_quote_minute
                WHERE trading_day = :today
                """, Map.of("today", LocalDate.now(CHINA_ZONE)));
    }

    public void clearUsPreOpenMinuteData() {
        LocalDate tradingDay = usLocalTime(LocalDateTime.now(CHINA_ZONE)).toLocalDate();
        jdbcTemplate.update("""
                DELETE mq
                FROM market_quote_minute mq
                JOIN security_instrument si ON si.id = mq.instrument_id
                WHERE mq.trading_day = :tradingDay
                  AND si.market IN (:markets)
                """, new MapSqlParameterSource()
                .addValue("tradingDay", tradingDay)
                .addValue("markets", List.of("NYSE", "NASDAQ", "AMEX")));
    }

    private void refreshIndexIntraday(String symbol) {
        refreshIndexIntraday(symbol, true);
    }

    private void refreshIndexIntraday(String symbol, boolean appendRealtimeQuote) {
        DefaultIndex fallback = defaultIndex(symbol);
        if (fallback == null) {
            return;
        }
        if (isOfficialGoldMarket(fallback)) {
            refreshOfficialGoldQuote(fallback);
            return;
        }
        if (isGlobalGoldMarket(fallback)) {
            refreshSinaGlobalFutureIntraday(fallback);
            return;
        }
        try {
            int sourceId = ensureEastmoneyIndexDataSource();
            JsonNode data = objectMapper.readTree(fetchUtf8Text(eastmoneyTrendUrl(fallback))).path("data");
            if (data.isMissingNode() || data.isNull()) {
                refreshFallbackIndexQuotes(fallback);
                return;
            }
            BigDecimal preClose = decimal(data.path("preClose").asText(null));
            JsonNode trends = data.path("trends");
            if (!trends.isArray()) {
                refreshFallbackIndexQuotes(fallback);
                return;
            }
            InstrumentRef instrument = upsertInstrument(fallback.market(), fallback.symbol(), data.path("name").asText(fallback.name()), sourceId);
            List<EastmoneyTrendPoint> validPoints = new ArrayList<>();
            for (JsonNode item : trends) {
                String[] fields = item.asText("").split(",", -1);
                if (fields.length < 7) {
                    continue;
                }
                LocalDateTime quoteTime = normalizeEastmoneyTrendTime(fallback, parseDateTime(fields[0]));
                BigDecimal openPrice = decimal(fields[1]);
                BigDecimal closePrice = decimal(fields[2]);
                BigDecimal highPrice = decimal(fields[3]);
                BigDecimal lowPrice = decimal(fields[4]);
                BigDecimal volume = decimal(fields[5]);
                BigDecimal turnover = decimal(fields[6]);
                LocalDate pointTradingDay = marketTradingDay(fallback, quoteTime);
                if (closePrice == null
                        || isFutureQuoteTime(quoteTime)
                        || !belongsToMinuteSession(fallback, pointTradingDay, quoteTime)) {
                    continue;
                }
                validPoints.add(new EastmoneyTrendPoint(
                        pointTradingDay,
                        quoteTime,
                        openPrice,
                        highPrice,
                        lowPrice,
                        closePrice,
                        ratioPct(closePrice, preClose),
                        volume,
                        turnover
                ));
            }
            if (validPoints.isEmpty()) {
                refreshFallbackIndexQuotes(fallback);
                return;
            }

            LocalDate expectedDay = expectedTradingDay(LocalDateTime.now(CHINA_ZONE), fallback);
            LocalDateTime latestExpectedQuoteTime = validPoints.stream()
                    .filter(point -> expectedDay.equals(point.tradingDay()))
                    .map(EastmoneyTrendPoint::quoteTime)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            if (isHkMarket(fallback) && latestExpectedQuoteTime != null) {
                cleanHkMinuteQuotes(instrument.id(), fallback.symbol(), expectedDay, latestExpectedQuoteTime, sourceId);
            }

            for (EastmoneyTrendPoint point : validPoints) {
                upsertMinuteQuote(
                        instrument.id(),
                        point.tradingDay(),
                        point.quoteTime(),
                        point.openPrice(),
                        point.highPrice(),
                        point.lowPrice(),
                        point.closePrice(),
                        preClose,
                        point.changePct(),
                        point.volume(),
                        point.turnover(),
                        sourceId
                );
            }
            int saved = validPoints.size();
            if (!isChinaMarket(fallback) && !isHkMarket(fallback) && saved < 30) {
                refreshFallbackIndexQuotes(fallback);
            } else if (appendRealtimeQuote && isTradingTime(LocalDateTime.now(CHINA_ZONE))) {
                fetchTencentIndexQuotes();
                fetchSinaIndexQuotes();
            }
        } catch (Exception ignored) {
            refreshFallbackIndexQuotes(fallback);
        }
    }

    private void cleanHkMinuteQuotes(long instrumentId,
                                     String symbol,
                                     LocalDate tradingDay,
                                     LocalDateTime latestValidQuoteTime,
                                     int sourceId) {
        jdbcTemplate.update("""
                DELETE FROM market_quote_minute
                WHERE instrument_id = :instrumentId
                  AND trading_day = :tradingDay
                  AND (
                    source_id IS NULL
                    OR source_id <> :sourceId
                    OR quote_time > :latestValidQuoteTime
                    OR NOT (
                      TIME(quote_time) BETWEEN '09:30:00' AND '12:00:00'
                      OR TIME(quote_time) BETWEEN '13:00:00' AND '16:00:00'
                    )
                  )
                """, new MapSqlParameterSource()
                .addValue("instrumentId", instrumentId)
                .addValue("tradingDay", tradingDay)
                .addValue("latestValidQuoteTime", latestValidQuoteTime)
                .addValue("sourceId", sourceId));
        intradayRedisCacheService.clearMarketMinutePoints(symbol, tradingDay);
    }

    /**
     * 针对主数据源刷新不稳定品种的兜底链路。
     *
     * 东方财富 trend 接口对不同市场支持不完全，因此港股、美股、黄金等品种会继续尝试
     * 新浪、Yahoo、期货接口或本地缓存。每个来源只负责自己更擅长的市场，最终统一写入
     * market_quote/minute 表和 Redis 缓存。
     */
    private void refreshFallbackIndexQuotes(DefaultIndex fallback) {
        if (isOfficialGoldMarket(fallback)) {
            refreshOfficialGoldQuote(fallback);
            return;
        }
        if (isGlobalGoldMarket(fallback)) {
            fetchSinaIndexQuotes();
            refreshSinaGlobalFutureIntraday(fallback);
            return;
        }
        fetchEastmoneyIndexQuotes();
        fetchTencentIndexQuotes();
        fetchSinaIndexQuotes();
        int saved = refreshSinaIndexIntraday(fallback);
        if (saved == 0) {
            saved = refreshEastmoneyFutureIntraday(fallback);
        }
        if (saved == 0) {
            saved = refreshSinaFutureIntraday(fallback);
        }
        if (saved == 0) {
            refreshYahooIndexIntraday(fallback);
        }
    }

    private List<MarketDtos.IndexQuoteResponse> fetchOfficialGoldQuotes() {
        List<MarketDtos.IndexQuoteResponse> quotes = new ArrayList<>();
        for (DefaultIndex index : DEFAULT_INDICES) {
            if (!isOfficialGoldMarket(index)) {
                continue;
            }
            MarketDtos.IndexQuoteResponse quote = refreshOfficialGoldQuote(index);
            if (quote != null && isExpectedTradingDayQuote(index, quote.quoteTime())) {
                quotes.add(quote);
            }
        }
        return sortDefaultIndices(quotes);
    }

    private boolean isExpectedTradingDayQuote(DefaultIndex index, LocalDateTime quoteTime) {
        if (index == null || quoteTime == null || isFutureQuoteTime(quoteTime)) {
            return false;
        }
        LocalDate expectedDay = expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
        return expectedDay.equals(marketTradingDay(index, quoteTime));
    }

    private MarketDtos.MinuteSeriesResponse officialGoldMinuteSeries(DefaultIndex index) {
        MarketDtos.IndexQuoteResponse quote = refreshOfficialGoldQuote(index);
        if (quote == null) {
            quote = loadLatestOfficialGoldQuote(index);
        }
        LocalDate expectedDay = expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
        if (quote == null || quote.lastPrice() == null || quote.quoteTime() == null) {
            return new MarketDtos.MinuteSeriesResponse(
                    index.market(),
                    index.symbol(),
                    index.name(),
                    expectedDay,
                    LocalDateTime.now(CHINA_ZONE),
                    "NO_DATA",
                    List.of()
            );
        }
        LocalDate tradingDay = isSgeMarket(index) ? expectedDay : marketTradingDay(index, quote.quoteTime());
        List<MarketDtos.MinutePointResponse> points = loadOfficialGoldMinutePoints(index, tradingDay);
        if ("AUM".equals(index.symbol()) && !tradingDay.equals(expectedDay)) {
            points = List.of();
        } else if (points.isEmpty()) {
            points = List.of(new MarketDtos.MinutePointResponse(
                    quote.quoteTime(),
                    quote.lastPrice(),
                    quote.changePct(),
                    null,
                    quote.turnover()
            ));
        }
        return new MarketDtos.MinuteSeriesResponse(
                quote.market(),
                quote.symbol(),
                quote.name(),
                tradingDay,
                quote.quoteTime(),
                tradingDay.equals(expectedDay) ? "NORMAL" : "STALE",
                points
        );
    }

    private MarketDtos.IndexQuoteResponse refreshOfficialGoldQuote(DefaultIndex index) {
        OfficialGoldQuote quote = readOfficialGoldQuote(index);
        if (quote == null) {
            return loadLatestOfficialGoldQuote(index);
        }
        return persistOfficialGoldQuote(quote, officialGoldSourceId(index), officialGoldSourceUrl(index));
    }

    private OfficialGoldQuote readOfficialGoldQuote(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        if ("AUM".equals(index.symbol())) {
            return readShfeGoldOfficialQuote(index);
        }
        if ("AU9999".equals(index.symbol())) {
            return readSgeSpotGoldOfficialQuote(index);
        }
        return null;
    }

    private OfficialGoldQuote readShfeGoldOfficialQuote(DefaultIndex index) {
        LocalDate cursor = expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
        for (int i = 0; i < 10; i += 1) {
            if (!isWeekend(cursor)) {
                MarketDtos.HistoryPointResponse point = fetchShfeGoldMainDailyBar(cursor);
                if (point != null) {
                    return officialGoldQuoteFromDailyBar(index, point, cursor.atTime(15, 0));
                }
            }
            cursor = previousWeekday(cursor.minusDays(1));
        }
        return null;
    }

    private OfficialGoldQuote readSgeSpotGoldOfficialQuote(DefaultIndex index) {
        OfficialGoldQuote delayedQuote = fetchSgeSpotGoldDelayedQuote(index);
        if (delayedQuote != null) {
            return delayedQuote;
        }
        LocalDate cursor = expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
        for (int i = 0; i < 10; i += 1) {
            if (!isWeekend(cursor)) {
                MarketDtos.HistoryPointResponse point = fetchSgeSpotGoldDailyBar(cursor);
                if (point != null) {
                    return officialGoldQuoteFromDailyBar(index, point, cursor.atTime(15, 30));
                }
            }
            cursor = previousWeekday(cursor.minusDays(1));
        }
        return null;
    }

    private OfficialGoldQuote officialGoldQuoteFromDailyBar(DefaultIndex index,
                                                            MarketDtos.HistoryPointResponse point,
                                                            LocalDateTime quoteTime) {
        if (index == null || point == null || point.closePrice() == null || quoteTime == null) {
            return null;
        }
        BigDecimal changeAmount = changeAmountFromPct(point.closePrice(), point.changePct());
        BigDecimal previousClose = changeAmount == null ? null : point.closePrice().subtract(changeAmount);
        return new OfficialGoldQuote(
                index,
                index.name(),
                point.closePrice(),
                previousClose,
                point.openPrice(),
                point.highPrice(),
                point.lowPrice(),
                changeAmount,
                point.changePct(),
                point.volume(),
                point.turnover(),
                point.tradingDay(),
                quoteTime,
                dataLagSeconds(quoteTime)
        );
    }

    private OfficialGoldQuote fetchSgeSpotGoldDelayedQuote(DefaultIndex index) {
        if (index == null || !"AU9999".equals(index.symbol())) {
            return null;
        }
        try {
            ensureSgeOfficialDataSource();
            String text = normalizeHtmlText(fetchUtf8Text(SGE_DELAYED_QUOTE_URL));
            Matcher row = SGE_DELAYED_AU9999_PATTERN.matcher(text);
            if (!row.find()) {
                return null;
            }
            BigDecimal lastPrice = decimal(row.group(1));
            BigDecimal highPrice = decimal(row.group(2));
            BigDecimal lowPrice = decimal(row.group(3));
            BigDecimal openPrice = decimal(row.group(4));
            if (lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            LocalDate tradingDay = parseSgeReportDate(text);
            if (tradingDay == null) {
                tradingDay = LocalDate.now(CHINA_ZONE);
            }
            MarketDtos.HistoryPointResponse previousBar = fetchSgeSpotGoldDailyBar(previousWeekday(tradingDay.minusDays(1)));
            BigDecimal previousClose = previousBar == null ? null : previousBar.closePrice();
            BigDecimal changeAmount = previousClose == null ? null : lastPrice.subtract(previousClose);
            LocalDateTime now = LocalDateTime.now(CHINA_ZONE).truncatedTo(ChronoUnit.MINUTES);
            LocalDateTime quoteTime = sgeDelayedQuoteTime(index, tradingDay, now);
            if (quoteTime == null) {
                return null;
            }
            return new OfficialGoldQuote(
                    index,
                    index.name(),
                    lastPrice,
                    previousClose,
                    openPrice,
                    highPrice,
                    lowPrice,
                    changeAmount,
                    ratioPct(lastPrice, previousClose),
                    null,
                    null,
                    tradingDay,
                    quoteTime,
                    dataLagSeconds(quoteTime)
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private LocalDateTime sgeDelayedQuoteTime(DefaultIndex index, LocalDate tradingDay, LocalDateTime now) {
        if (index == null || tradingDay == null || now == null) {
            return null;
        }
        if (!tradingDay.equals(expectedTradingDay(now, index))) {
            return tradingDay.atTime(15, 30);
        }

        LocalDateTime delayed = now.minusMinutes(15).truncatedTo(ChronoUnit.MINUTES);
        if (belongsToMinuteSession(index, tradingDay, delayed)) {
            return delayed;
        }

        LocalDateTime afternoonClose = tradingDay.atTime(15, 30);
        LocalDateTime morningClose = tradingDay.atTime(11, 30);
        LocalDateTime nightClose = tradingDay.atTime(2, 30);
        if (!delayed.isBefore(afternoonClose)) {
            return afternoonClose;
        }
        if (!delayed.isBefore(morningClose)) {
            return morningClose;
        }
        if (!delayed.isBefore(nightClose)) {
            return nightClose;
        }
        return null;
    }

    private MarketDtos.IndexQuoteResponse persistOfficialGoldQuote(OfficialGoldQuote quote, int sourceId, String sourceUrl) {
        if (quote == null || quote.index() == null || quote.lastPrice() == null || quote.quoteTime() == null
                || isFutureQuoteTime(quote.quoteTime())) {
            return null;
        }
        DefaultIndex index = quote.index();
        InstrumentRef instrument = upsertInstrument(
                index.market(),
                index.symbol(),
                quote.name() == null || quote.name().isBlank() ? index.name() : quote.name(),
                sourceId,
                sourceUrl
        );
        upsertLatestQuote(
                instrument.id(),
                quote.quoteTime(),
                quote.lastPrice(),
                quote.prevClose(),
                quote.openPrice(),
                quote.highPrice(),
                quote.lowPrice(),
                quote.changeAmount(),
                quote.changePct(),
                quote.volume(),
                quote.turnover(),
                quote.dataLagSeconds(),
                sourceId
        );
        upsertMinuteQuote(
                instrument.id(),
                quote.tradingDay() == null ? marketTradingDay(index, quote.quoteTime()) : quote.tradingDay(),
                quote.quoteTime(),
                quote.openPrice(),
                quote.highPrice(),
                quote.lowPrice(),
                quote.lastPrice(),
                quote.prevClose(),
                quote.changePct(),
                quote.volume(),
                quote.turnover(),
                sourceId
        );
        return new MarketDtos.IndexQuoteResponse(
                index.market(),
                index.symbol(),
                quote.name() == null || quote.name().isBlank() ? index.name() : quote.name(),
                quote.lastPrice(),
                quote.changeAmount(),
                quote.changePct(),
                quote.turnover(),
                quote.dataLagSeconds(),
                quote.quoteTime()
        );
    }

    private MarketDtos.IndexQuoteResponse loadLatestOfficialGoldQuote(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        List<InstrumentRef> instruments = findIndexInstruments(index.symbol());
        if (instruments.isEmpty()) {
            return null;
        }
        List<MarketDtos.IndexQuoteResponse> rows = jdbcTemplate.query("""
                SELECT mq.last_price,
                       mq.change_amount,
                       mq.change_pct,
                       mq.turnover,
                       mq.data_lag_seconds,
                       mq.quote_time
                FROM market_quote_latest mq
                JOIN data_source ds ON ds.id = mq.source_id
                WHERE mq.instrument_id = :instrumentId
                  AND ds.source_code IN (:sourceCodes)
                  AND mq.last_price IS NOT NULL
                ORDER BY mq.quote_time DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("instrumentId", instruments.get(0).id())
                .addValue("sourceCodes", officialGoldSourceCodes(index)), (rs, rowNum) ->
                new MarketDtos.IndexQuoteResponse(
                        index.market(),
                        index.symbol(),
                        instruments.get(0).name(),
                        rs.getBigDecimal("last_price"),
                        rs.getBigDecimal("change_amount"),
                        rs.getBigDecimal("change_pct"),
                        rs.getBigDecimal("turnover"),
                        rs.getObject("data_lag_seconds") == null ? null : rs.getInt("data_lag_seconds"),
                        timestamp(rs.getTimestamp("quote_time"))
                ));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<MarketDtos.MinutePointResponse> loadOfficialGoldMinutePoints(DefaultIndex index, LocalDate tradingDay) {
        if (index == null || tradingDay == null) {
            return List.of();
        }
        List<InstrumentRef> instruments = findIndexInstruments(index.symbol());
        if (instruments.isEmpty()) {
            return List.of();
        }
        List<MarketDtos.MinutePointResponse> points = jdbcTemplate.query("""
                SELECT mq.quote_time,
                       COALESCE(mq.close_price, mq.open_price) AS price,
                       mq.change_pct,
                       mq.volume,
                       mq.turnover
                FROM market_quote_minute mq
                JOIN data_source ds ON ds.id = mq.source_id
                WHERE mq.instrument_id = :instrumentId
                  AND mq.trading_day = :tradingDay
                  AND ds.source_code IN (:sourceCodes)
                  AND COALESCE(mq.close_price, mq.open_price) IS NOT NULL
                ORDER BY mq.quote_time
                """, new MapSqlParameterSource()
                .addValue("instrumentId", instruments.get(0).id())
                .addValue("tradingDay", tradingDay)
                .addValue("sourceCodes", officialGoldSourceCodes(index)), (rs, rowNum) ->
                new MarketDtos.MinutePointResponse(
                        timestamp(rs.getTimestamp("quote_time")),
                        rs.getBigDecimal("price"),
                        rs.getBigDecimal("change_pct"),
                        rs.getBigDecimal("volume"),
                        rs.getBigDecimal("turnover")
                ));
        return sanitizeMinutePoints(points, index, tradingDay);
    }

    private int officialGoldSourceId(DefaultIndex index) {
        return "AUM".equals(index.symbol()) ? ensureShfeOfficialDataSource() : ensureSgeOfficialDataSource();
    }

    private String officialGoldSourceUrl(DefaultIndex index) {
        return "AUM".equals(index.symbol()) ? "https://www.shfe.cn/data/tradedata/future/dailydata/" : SGE_DELAYED_QUOTE_URL;
    }

    private List<String> officialGoldSourceCodes(DefaultIndex index) {
        if (index != null && "AUM".equals(index.symbol())) {
            return List.of("SHFE_OFFICIAL_DAILY");
        }
        return List.of("SGE_OFFICIAL_DELAYED_15M");
    }

    private int refreshSinaIndexIntraday(DefaultIndex index) {
        String minuteSymbol = sinaMinuteSymbol(index);
        if (minuteSymbol == null || minuteSymbol.isBlank()) {
            return 0;
        }
        try {
            int sourceId = ensureSinaMarketDataSource();
            SinaQuote latestQuote = fetchSinaQuote(index);
            persistSinaQuote(latestQuote, sourceId);
            String url = SINA_HK_MINUTE_URL + "?symbol=" + urlEncode(minuteSymbol);
            JsonNode rows = findArrayWithFields(objectMapper.readTree(fetchSinaText(url, StandardCharsets.UTF_8)), "m", "p");
            if (rows == null || !rows.isArray()) {
                return 0;
            }
            LocalDate tradingDay = latestQuote != null && latestQuote.quoteTime() != null
                    ? latestQuote.quoteTime().toLocalDate()
                    : expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
            BigDecimal prevClose = latestQuote == null ? null : latestQuote.prevClose();
            String name = latestQuote == null || latestQuote.name() == null || latestQuote.name().isBlank()
                    ? index.name()
                    : latestQuote.name();
            InstrumentRef instrument = upsertInstrument(index.market(), index.symbol(), name, sourceId, SINA_HK_MINUTE_URL);
            int saved = 0;
            for (JsonNode row : rows) {
                String timeText = row.path("m").asText("");
                LocalTime quoteTime = parseSinaTime(timeText);
                BigDecimal price = decimal(row.path("p").asText(null));
                if (quoteTime == null || price == null) {
                    continue;
                }
                LocalDateTime pointTime = tradingDay.atTime(quoteTime);
                if (isFutureQuoteTime(pointTime) || !belongsToMinuteSession(index, tradingDay, pointTime)) {
                    continue;
                }
                BigDecimal changePct = ratioPct(price, prevClose);
                upsertMinuteQuote(
                        instrument.id(),
                        pointTime,
                        null,
                        price,
                        price,
                        price,
                        prevClose,
                        changePct,
                        decimal(row.path("v").asText(null)),
                        decimal(row.path("a").asText(null)),
                        sourceId
                );
                saved += 1;
            }
            return saved;
        } catch (Exception exception) {
            return 0;
        }
    }

    private int refreshSinaFutureIntraday(DefaultIndex index) {
        String futureSymbol = sinaFutureSymbol(index);
        if (futureSymbol == null || futureSymbol.isBlank()) {
            return 0;
        }
        try {
            int sourceId = ensureSinaMarketDataSource();
            SinaQuote latestQuote = fetchSinaQuote(index);
            persistSinaQuote(latestQuote, sourceId);
            String url = SINA_FUTURES_MINUTE_URL + "?symbol=" + urlEncode(futureSymbol);
            JsonNode rows = firstDataArray(objectMapper.readTree(fetchSinaText(url, StandardCharsets.UTF_8)));
            if (rows == null || !rows.isArray()) {
                return 0;
            }
            LocalDate tradingDay = latestQuote != null && latestQuote.quoteTime() != null
                    ? latestQuote.quoteTime().toLocalDate()
                    : expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
            BigDecimal prevClose = latestQuote == null ? null : latestQuote.prevClose();
            String name = latestQuote == null || latestQuote.name() == null || latestQuote.name().isBlank()
                    ? index.name()
                    : latestQuote.name();
            InstrumentRef instrument = upsertInstrument(index.market(), index.symbol(), name, sourceId, SINA_FUTURES_MINUTE_URL);
            int saved = 0;
            for (JsonNode row : rows) {
                LocalDateTime quoteTime = sinaRowDateTime(row, tradingDay);
                BigDecimal closePrice = rowDecimal(row, "c", "close", "p", "price", 4, 1);
                if (quoteTime == null || closePrice == null) {
                    continue;
                }
                upsertMinuteQuote(
                        instrument.id(),
                        marketTradingDay(index, quoteTime),
                        quoteTime,
                        rowDecimal(row, "o", "open", 1),
                        rowDecimal(row, "h", "high", 2),
                        rowDecimal(row, "l", "low", 3),
                        closePrice,
                        prevClose,
                        ratioPct(closePrice, prevClose),
                        rowDecimal(row, "v", "volume", 5),
                        null,
                        sourceId
                );
                saved += 1;
            }
            return saved;
        } catch (Exception exception) {
            return 0;
        }
    }

    private int refreshSinaGlobalFutureIntraday(DefaultIndex index) {
        String globalSymbol = sinaGlobalFutureSymbol(index);
        if (globalSymbol == null || globalSymbol.isBlank()) {
            return 0;
        }
        try {
            int sourceId = ensureSinaMarketDataSource();
            SinaQuote latestQuote = fetchSinaQuote(index);
            persistSinaQuote(latestQuote, sourceId);
            String url = SINA_GLOBAL_FUTURES_MINUTE_URL + "?symbol=" + urlEncode(globalSymbol);
            JsonNode rows = objectMapper.readTree(fetchSinaText(url, StandardCharsets.UTF_8)).path("minLine_1d");
            if (!rows.isArray()) {
                return 0;
            }
            BigDecimal prevClose = latestQuote == null ? null : latestQuote.prevClose();
            String name = latestQuote == null || latestQuote.name() == null || latestQuote.name().isBlank()
                    ? index.name()
                    : latestQuote.name();
            InstrumentRef instrument = upsertInstrument(index.market(), index.symbol(), name, sourceId, SINA_GLOBAL_FUTURES_MINUTE_URL);
            int saved = 0;
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 2) {
                    continue;
                }
                LocalDateTime quoteTime = parseSinaDateTime(row.get(row.size() - 1).asText(null));
                BigDecimal closePrice = decimal(row.path(1).asText(null));
                LocalDate tradingDay = marketTradingDay(index, quoteTime);
                if (quoteTime == null
                        || closePrice == null
                        || isFutureQuoteTime(quoteTime)
                        || !belongsToMinuteSession(index, tradingDay, quoteTime)) {
                    continue;
                }
                upsertMinuteQuote(
                        instrument.id(),
                        tradingDay,
                        quoteTime,
                        null,
                        null,
                        null,
                        closePrice,
                        prevClose,
                        ratioPct(closePrice, prevClose),
                        null,
                        null,
                        sourceId
                );
                saved += 1;
            }
            return saved;
        } catch (Exception exception) {
            return 0;
        }
    }

    private int refreshEastmoneyFutureIntraday(DefaultIndex index) {
        String staticCode = eastmoneyFutureStaticCode(index);
        if (staticCode == null || staticCode.isBlank()) {
            return 0;
        }
        try {
            int sourceId = ensureEastmoneyFuturesDataSource();
            EastmoneyFutureQuote latestQuote = readEastmoneyFutureQuote(index);
            persistEastmoneyFutureQuote(latestQuote, sourceId);
            String url = EASTMONEY_FUTURES_API_URL + "static/" + staticCode + "_mx/240?token=" + EASTMONEY_FUTURES_TOKEN;
            JsonNode rows = objectMapper.readTree(fetchUtf8Text(url)).path("mx");
            if (!rows.isArray()) {
                return 0;
            }
            List<FutureTick> ticks = new ArrayList<>();
            for (JsonNode row : rows) {
                Long epochSeconds = longValue(row.path("utime"));
                BigDecimal price = decimal(row.path("p").asText(null));
                if (epochSeconds == null || price == null) {
                    continue;
                }
                LocalDateTime quoteTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), CHINA_ZONE);
                ticks.add(new FutureTick(
                        quoteTime,
                        price,
                        decimal(row.path("vol").asText(null))
                ));
            }
            if (ticks.isEmpty()) {
                return 0;
            }
            ticks.sort((left, right) -> left.quoteTime().compareTo(right.quoteTime()));
            Map<LocalDateTime, FutureMinuteBar> bars = new LinkedHashMap<>();
            for (FutureTick tick : ticks) {
                LocalDateTime minute = tick.quoteTime().truncatedTo(ChronoUnit.MINUTES);
                bars.computeIfAbsent(minute, FutureMinuteBar::new).add(tick);
            }
            String name = latestQuote == null || latestQuote.name() == null || latestQuote.name().isBlank()
                    ? index.name()
                    : latestQuote.name();
            BigDecimal prevClose = latestQuote == null ? null : latestQuote.prevClose();
            InstrumentRef instrument = upsertInstrument(index.market(), index.symbol(), name, sourceId, EASTMONEY_FUTURES_API_URL);
            int saved = 0;
            for (FutureMinuteBar bar : bars.values()) {
                upsertMinuteQuote(
                        instrument.id(),
                        marketTradingDay(index, bar.minute()),
                        bar.minute(),
                        bar.openPrice(),
                        bar.highPrice(),
                        bar.lowPrice(),
                        bar.closePrice(),
                        prevClose,
                        ratioPct(bar.closePrice(), prevClose),
                        bar.volume(),
                        null,
                        sourceId
                );
                saved += 1;
            }
            return saved;
        } catch (Exception exception) {
            return 0;
        }
    }

    private int refreshYahooIndexIntraday(DefaultIndex index) {
        String chartSymbol = yahooChartSymbol(index);
        if (chartSymbol == null || chartSymbol.isBlank()) {
            return 0;
        }
        try {
            JsonNode result = yahooChartResult(yahooChartUrl(chartSymbol, "1d", "1m"));
            if (result == null) {
                return 0;
            }
            JsonNode timestamps = result.path("timestamp");
            JsonNode quote = result.path("indicators").path("quote").path(0);
            if (!timestamps.isArray() || quote.isMissingNode()) {
                return 0;
            }
            int sourceId = ensureYahooMarketDataSource();
            String name = result.path("meta").path("longName").asText(index.name());
            BigDecimal prevClose = firstDecimal(
                    result.path("meta").path("previousClose").asText(null),
                    result.path("meta").path("chartPreviousClose").asText(null),
                    result.path("meta").path("regularMarketPreviousClose").asText(null)
            );
            InstrumentRef instrument = upsertInstrument(index.market(), index.symbol(), name, sourceId, YAHOO_CHART_URL);
            int saved = 0;
            MarketDtos.MinutePointResponse latest = null;
            for (int i = 0; i < timestamps.size(); i += 1) {
                Long epochSeconds = longValue(timestamps.get(i));
                BigDecimal closePrice = decimalAt(quote.path("close"), i);
                if (epochSeconds == null || closePrice == null) {
                    continue;
                }
                LocalDateTime quoteTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), CHINA_ZONE);
                BigDecimal changePct = ratioPct(closePrice, prevClose);
                upsertMinuteQuote(
                        instrument.id(),
                        marketTradingDay(index, quoteTime),
                        quoteTime,
                        decimalAt(quote.path("open"), i),
                        decimalAt(quote.path("high"), i),
                        decimalAt(quote.path("low"), i),
                        closePrice,
                        prevClose,
                        changePct,
                        decimalAt(quote.path("volume"), i),
                        null,
                        sourceId
                );
                latest = new MarketDtos.MinutePointResponse(quoteTime, closePrice, changePct, decimalAt(quote.path("volume"), i), null);
                saved += 1;
            }
            if (latest != null) {
                upsertLatestQuote(
                        instrument.id(),
                        latest.quoteTime(),
                        latest.price(),
                        prevClose,
                        null,
                        null,
                        null,
                        latest.price() != null && prevClose != null ? latest.price().subtract(prevClose) : null,
                        latest.changePct(),
                        latest.volume(),
                        null,
                        dataLagSeconds(latest.quoteTime()),
                        sourceId
                );
            }
            return saved;
        } catch (Exception exception) {
            return 0;
        }
    }

    private List<MarketDtos.IndexQuoteResponse> fetchTencentIndexQuotes() {
        try {
            String body = fetchGbkText(TENCENT_INDEX_QUOTE_URL);
            Matcher matcher = TENCENT_QUOTE_PATTERN.matcher(body);
            int sourceId = ensureTencentDataSource();
            List<MarketDtos.IndexQuoteResponse> quotes = new ArrayList<>();

            while (matcher.find()) {
                String prefix = matcher.group(1);
                String payload = matcher.group(3);
                String[] fields = payload.split("~", -1);
                if (fields.length <= 39) {
                    continue;
                }
                String market = "sh".equals(prefix) ? "SSE" : "SZSE";
                String name = fields[1];
                String symbol = fields[2];
                BigDecimal lastPrice = decimal(fields[3]);
                BigDecimal prevClose = decimal(fields[4]);
                BigDecimal openPrice = decimal(fields[5]);
                BigDecimal changeAmount = decimal(fields[31]);
                BigDecimal changePct = decimal(fields[32]);
                BigDecimal highPrice = decimal(fields[33]);
                BigDecimal lowPrice = decimal(fields[34]);
                BigDecimal volume = decimal(fields[36]);
                BigDecimal turnover = tencentTurnover(fields[35], fields[37]);
                LocalDateTime quoteTime = parseTencentTime(fields[30]);
                LocalDateTime minuteQuoteTime = normalizeMinuteQuoteTime(quoteTime);
                int dataLagSeconds = Math.max(0, (int) Duration.between(quoteTime, LocalDateTime.now(CHINA_ZONE)).getSeconds());

                InstrumentRef instrument = upsertInstrument(market, symbol, name, sourceId);
                upsertLatestQuote(
                        instrument.id(),
                        quoteTime,
                        lastPrice,
                        prevClose,
                        openPrice,
                        highPrice,
                        lowPrice,
                        changeAmount,
                        changePct,
                        volume,
                        turnover,
                        dataLagSeconds,
                        sourceId
                );
                upsertMinuteQuote(
                        instrument.id(),
                        minuteQuoteTime,
                        openPrice,
                        highPrice,
                        lowPrice,
                        lastPrice,
                        prevClose,
                        changePct,
                        volume,
                        turnover,
                        sourceId
                );
                quotes.add(new MarketDtos.IndexQuoteResponse(
                        market,
                        symbol,
                        name,
                        lastPrice,
                        changeAmount,
                        changePct,
                        turnover,
                        dataLagSeconds,
                        quoteTime
                ));
            }
            return sortDefaultIndices(quotes);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.IndexQuoteResponse> fetchEastmoneyIndexQuotes() {
        String secids = DEFAULT_INDICES.stream()
                .filter(index -> !"UDI".equals(index.symbol()))
                .map(DefaultIndex::eastmoneySecid)
                .filter(code -> code != null && !code.isBlank())
                .reduce((left, right) -> left + "," + right)
                .orElse("1.000001");
        String url = EASTMONEY_INDEX_QUOTE_URL
                + "?fltt=2"
                + "&invt=2"
                + "&fields=f12,f13,f14,f2,f3,f4,f6,f18,f124"
                + "&secids=" + secids
                + "&_=" + System.currentTimeMillis();
        try {
            JsonNode diff = objectMapper.readTree(fetchUtf8Text(url))
                    .path("data")
                    .path("diff");
            if (!diff.isArray()) {
                return List.of();
            }
            int sourceId = ensureEastmoneyIndexDataSource();
            List<MarketDtos.IndexQuoteResponse> quotes = new ArrayList<>();
            for (JsonNode item : diff) {
                String rawSymbol = item.path("f12").asText("");
                DefaultIndex defaultIndex = defaultIndex(rawSymbol);
                if (defaultIndex == null) {
                    continue;
                }
                String symbol = defaultIndex.symbol();
                String market = defaultIndex.market();
                String name = item.path("f14").asText(defaultIndex.name());
                BigDecimal lastPrice = decimal(item.path("f2").asText(null));
                BigDecimal changePct = decimal(item.path("f3").asText(null));
                BigDecimal changeAmount = decimal(item.path("f4").asText(null));
                BigDecimal turnover = decimal(item.path("f6").asText(null));
                BigDecimal prevClose = decimal(item.path("f18").asText(null));
                LocalDateTime quoteTime = eastmoneyQuoteTime(item.path("f124").asText(null));
                if (lastPrice == null || quoteTime == null || isFutureQuoteTime(quoteTime)) {
                    continue;
                }
                int dataLagSeconds = Math.max(0, (int) Duration.between(quoteTime, LocalDateTime.now(CHINA_ZONE)).getSeconds());

                InstrumentRef instrument = upsertInstrument(market, symbol, name, sourceId);
                upsertLatestQuote(
                        instrument.id(),
                        quoteTime,
                        lastPrice,
                        prevClose,
                        null,
                        null,
                        null,
                        changeAmount,
                        changePct,
                        null,
                        turnover,
                        dataLagSeconds,
                        sourceId
                );
                quotes.add(new MarketDtos.IndexQuoteResponse(
                        market,
                        symbol,
                        name,
                        lastPrice,
                        changeAmount,
                        changePct,
                        turnover,
                        dataLagSeconds,
                        quoteTime
                ));
            }
            return sortDefaultIndices(quotes);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.IndexQuoteResponse> fetchEastmoneyFutureQuotes() {
        try {
            int sourceId = ensureEastmoneyFuturesDataSource();
            List<MarketDtos.IndexQuoteResponse> quotes = new ArrayList<>();
            for (DefaultIndex index : DEFAULT_INDICES) {
                if (eastmoneyFutureStaticCode(index) == null) {
                    continue;
                }
                MarketDtos.IndexQuoteResponse quote = persistEastmoneyFutureQuote(readEastmoneyFutureQuote(index), sourceId);
                if (quote != null) {
                    quotes.add(quote);
                }
            }
            return sortDefaultIndices(quotes);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private EastmoneyFutureQuote readEastmoneyFutureQuote(DefaultIndex index) {
        String staticCode = eastmoneyFutureStaticCode(index);
        if (staticCode == null || staticCode.isBlank()) {
            return null;
        }
        try {
            String url = EASTMONEY_FUTURES_API_URL + "static/" + staticCode + "_qt"
                    + "?field=" + urlEncode(EASTMONEY_FUTURES_FIELDS)
                    + "&token=" + EASTMONEY_FUTURES_TOKEN;
            JsonNode quote = objectMapper.readTree(fetchUtf8Text(url)).path("qt");
            BigDecimal lastPrice = decimal(quote.path("p").asText(null));
            Long epochSeconds = longValue(quote.path("utime"));
            if (lastPrice == null || epochSeconds == null) {
                return null;
            }
            return new EastmoneyFutureQuote(
                    index,
                    quote.path("name").asText(index.name()),
                    lastPrice,
                    decimal(quote.path("zjsj").asText(null)),
                    decimal(quote.path("o").asText(null)),
                    decimal(quote.path("h").asText(null)),
                    decimal(quote.path("l").asText(null)),
                    decimal(quote.path("zde").asText(null)),
                    decimal(quote.path("zdf").asText(null)),
                    decimal(quote.path("vol").asText(null)),
                    decimal(quote.path("cje").asText(null)),
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), CHINA_ZONE)
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private MarketDtos.IndexQuoteResponse persistEastmoneyFutureQuote(EastmoneyFutureQuote quote, int sourceId) {
        if (quote == null || quote.index() == null || quote.lastPrice() == null || quote.quoteTime() == null) {
            return null;
        }
        DefaultIndex index = quote.index();
        BigDecimal changeAmount = quote.changeAmount();
        if (changeAmount == null && quote.prevClose() != null) {
            changeAmount = quote.lastPrice().subtract(quote.prevClose());
        }
        BigDecimal changePct = quote.changePct() == null
                ? ratioPct(quote.lastPrice(), quote.prevClose())
                : quote.changePct();
        int dataLagSeconds = dataLagSeconds(quote.quoteTime());
        InstrumentRef instrument = upsertInstrument(
                index.market(),
                index.symbol(),
                quote.name() == null || quote.name().isBlank() ? index.name() : quote.name(),
                sourceId,
                EASTMONEY_FUTURES_API_URL
        );
        upsertLatestQuote(
                instrument.id(),
                quote.quoteTime(),
                quote.lastPrice(),
                quote.prevClose(),
                quote.openPrice(),
                quote.highPrice(),
                quote.lowPrice(),
                changeAmount,
                changePct,
                quote.volume(),
                quote.turnover(),
                dataLagSeconds,
                sourceId
        );
        return new MarketDtos.IndexQuoteResponse(
                index.market(),
                index.symbol(),
                quote.name() == null || quote.name().isBlank() ? index.name() : quote.name(),
                quote.lastPrice(),
                changeAmount,
                changePct,
                quote.turnover(),
                dataLagSeconds,
                quote.quoteTime()
        );
    }

    private List<MarketDtos.IndexQuoteResponse> fetchSinaIndexQuotes() {
        String codes = DEFAULT_INDICES.stream()
                .filter(index -> !"UDI".equals(index.symbol()))
                .map(this::sinaQuoteCode)
                .filter(code -> code != null && !code.isBlank())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (codes.isBlank()) {
            return List.of();
        }
        try {
            String body = fetchSinaText(SINA_QUOTE_URL + codes, GBK);
            Matcher matcher = SINA_QUOTE_PATTERN.matcher(body);
            int sourceId = ensureSinaMarketDataSource();
            List<MarketDtos.IndexQuoteResponse> quotes = new ArrayList<>();
            while (matcher.find()) {
                DefaultIndex index = defaultIndexBySinaQuoteCode(matcher.group(1));
                if (index == null) {
                    continue;
                }
                SinaQuote quote = parseSinaQuote(index, matcher.group(2));
                MarketDtos.IndexQuoteResponse response = persistSinaQuote(quote, sourceId);
                if (response != null) {
                    quotes.add(response);
                }
            }
            return sortDefaultIndices(quotes);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private MarketDtos.IndexQuoteResponse persistSinaQuote(SinaQuote quote, int sourceId) {
        if (quote == null || quote.index() == null || quote.lastPrice() == null || quote.quoteTime() == null
                || isFutureQuoteTime(quote.quoteTime())) {
            return null;
        }
        DefaultIndex index = quote.index();
        BigDecimal changeAmount = quote.changeAmount();
        if (changeAmount == null && quote.prevClose() != null) {
            changeAmount = quote.lastPrice().subtract(quote.prevClose());
        }
        BigDecimal changePct = quote.changePct() == null
                ? ratioPct(quote.lastPrice(), quote.prevClose())
                : quote.changePct();
        int dataLagSeconds = dataLagSeconds(quote.quoteTime());
        InstrumentRef instrument = upsertInstrument(
                index.market(),
                index.symbol(),
                quote.name() == null || quote.name().isBlank() ? index.name() : quote.name(),
                sourceId,
                SINA_QUOTE_URL
        );
        upsertLatestQuote(
                instrument.id(),
                quote.quoteTime(),
                quote.lastPrice(),
                quote.prevClose(),
                quote.openPrice(),
                quote.highPrice(),
                quote.lowPrice(),
                changeAmount,
                changePct,
                quote.volume(),
                quote.turnover(),
                dataLagSeconds,
                sourceId
        );
        return new MarketDtos.IndexQuoteResponse(
                index.market(),
                index.symbol(),
                quote.name() == null || quote.name().isBlank() ? index.name() : quote.name(),
                quote.lastPrice(),
                changeAmount,
                changePct,
                quote.turnover(),
                dataLagSeconds,
                quote.quoteTime()
        );
    }

    private SinaQuote fetchSinaQuote(DefaultIndex index) {
        String code = sinaQuoteCode(index);
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            String body = fetchSinaText(SINA_QUOTE_URL + code, GBK);
            Matcher matcher = SINA_QUOTE_PATTERN.matcher(body);
            if (!matcher.find()) {
                return null;
            }
            return parseSinaQuote(index, matcher.group(2));
        } catch (Exception exception) {
            return null;
        }
    }

    private SinaQuote parseSinaQuote(DefaultIndex index, String payload) {
        if (index == null || payload == null || payload.isBlank()) {
            return null;
        }
        String[] fields = payload.split(",", -1);
        if ("HKEX".equals(index.market())) {
            return parseSinaHkQuote(index, fields);
        }
        if ("NYSE".equals(index.market()) || "NASDAQ".equals(index.market()) || "AMEX".equals(index.market())) {
            return parseSinaUsQuote(index, fields);
        }
        if (isGlobalGoldMarket(index)) {
            return parseSinaSpotGoldQuote(index, fields);
        }
        if ("AUM".equals(index.symbol())) {
            return parseSinaFutureGoldQuote(index, fields);
        }
        return null;
    }

    private SinaQuote parseSinaHkQuote(DefaultIndex index, String[] fields) {
        if (fields.length < 9) {
            return null;
        }
        BigDecimal lastPrice = decimalField(fields, 6);
        BigDecimal prevClose = decimalField(fields, 3);
        return new SinaQuote(
                index,
                textField(fields, 1, index.name()),
                lastPrice,
                prevClose,
                decimalField(fields, 2),
                decimalField(fields, 4),
                decimalField(fields, 5),
                decimalField(fields, 7),
                decimalField(fields, 8),
                firstDecimalField(fields, 9, 10),
                firstDecimalField(fields, 11, 12),
                parseSinaQuoteTime(fields)
        );
    }

    private SinaQuote parseSinaUsQuote(DefaultIndex index, String[] fields) {
        if (fields.length < 4) {
            return null;
        }
        BigDecimal lastPrice = decimalField(fields, 1);
        BigDecimal changeAmount = decimalField(fields, 2);
        BigDecimal prevClose = lastPrice != null && changeAmount != null ? lastPrice.subtract(changeAmount) : null;
        return new SinaQuote(
                index,
                textField(fields, 0, index.name()),
                lastPrice,
                prevClose,
                decimalField(fields, 5),
                decimalField(fields, 6),
                decimalField(fields, 7),
                changeAmount,
                decimalField(fields, 3),
                firstDecimalField(fields, 10, 11),
                firstDecimalField(fields, 12, 13),
                parseSinaQuoteTime(fields)
        );
    }

    private SinaQuote parseSinaFutureGoldQuote(DefaultIndex index, String[] fields) {
        if (fields.length < 9) {
            return null;
        }
        BigDecimal lastPrice = firstDecimalField(fields, 8, 6, 2);
        BigDecimal prevClose = firstDecimalField(fields, 26, 10);
        return new SinaQuote(
                index,
                textField(fields, 0, index.name()),
                lastPrice,
                prevClose,
                decimalField(fields, 2),
                decimalField(fields, 3),
                decimalField(fields, 4),
                lastPrice != null && prevClose != null ? lastPrice.subtract(prevClose) : null,
                ratioPct(lastPrice, prevClose),
                decimalField(fields, 13),
                null,
                parseSinaFutureQuoteTime(fields)
        );
    }

    private SinaQuote parseSinaSpotGoldQuote(DefaultIndex index, String[] fields) {
        if (fields.length < 7) {
            return null;
        }
        BigDecimal lastPrice = firstDecimalField(fields, 0, 3);
        BigDecimal prevClose = firstDecimalField(fields, 8, 1);
        return new SinaQuote(
                index,
                textField(fields, 13, index.name()),
                lastPrice,
                prevClose,
                firstDecimalField(fields, 7, 2),
                decimalField(fields, 4),
                decimalField(fields, 5),
                lastPrice != null && prevClose != null ? lastPrice.subtract(prevClose) : null,
                ratioPct(lastPrice, prevClose),
                null,
                null,
                parseSinaSpotGoldQuoteTime(fields)
        );
    }

    private List<MarketDtos.SectorRankingResponse> fetchEastmoneySectorRankings(int limit) {
        String url = EASTMONEY_SECTOR_RANKING_URL
                + "?pn=1"
                + "&pz=" + limit
                + "&po=1"
                + "&np=1"
                + "&fltt=2"
                + "&invt=2"
                + "&fid=f3"
                + "&fs=m:90+t:2"
                + "&fields=f12,f14,f2,f3,f4,f62"
                + "&_=" + System.currentTimeMillis();
        try {
            JsonNode diff = objectMapper.readTree(fetchUtf8Text(url))
                    .path("data")
                    .path("diff");
            if (!diff.isArray()) {
                return List.of();
            }
            List<MarketDtos.SectorRankingResponse> rows = new ArrayList<>();
            for (JsonNode item : diff) {
                BigDecimal changePct = decimal(item.path("f3").asText(null));
                rows.add(new MarketDtos.SectorRankingResponse(
                        item.path("f12").asText(""),
                        item.path("f14").asText(""),
                        decimal(item.path("f2").asText(null)),
                        decimal(item.path("f4").asText(null)),
                        changePct,
                        decimal(item.path("f62").asText(null)),
                        direction(changePct)
                ));
            }
            return rows;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private synchronized FundRankSnapshot fetchEastmoneyFundRankSnapshot() {
        Instant now = Instant.now();
        CachedFundRankSnapshot cached = cachedFundRankSnapshot;
        if (cached != null && Duration.between(cached.fetchedAt(), now).compareTo(FUND_RANK_CACHE_TTL) < 0) {
            return cached.snapshot();
        }
        FundRankSnapshot snapshot = loadEastmoneyFundRankSnapshot();
        if (snapshot != null && snapshot.breadth().totalCount() > 0) {
            cachedFundRankSnapshot = new CachedFundRankSnapshot(now, snapshot);
        }
        return snapshot;
    }

    private FundRankSnapshot loadEastmoneyFundRankSnapshot() {
        try {
            String body = fetchUtf8Text(eastmoneyFundRankingUrl());
            int start = body.indexOf('{');
            int end = body.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            JsonNode root = objectMapper.readTree(normalizeEastmoneyJson(body.substring(start, end + 1)));
            JsonNode datas = root.path("datas");
            if (!datas.isArray()) {
                return null;
            }

            List<MarketDtos.FundRankingResponse> allRows = new ArrayList<>();
            for (JsonNode item : datas) {
                MarketDtos.FundRankingResponse row = parseEastmoneyFundRankRow(item.asText(""));
                if (row != null) {
                    allRows.add(row);
                }
            }
            if (allRows.isEmpty()) {
                return null;
            }

            int upCount = 0;
            int downCount = 0;
            for (MarketDtos.FundRankingResponse row : allRows) {
                BigDecimal pct = row.returnPct();
                if (pct == null || BigDecimal.ZERO.compareTo(pct) == 0) {
                    continue;
                }
                if (pct.compareTo(BigDecimal.ZERO) > 0) {
                    upCount++;
                } else {
                    downCount++;
                }
            }
            int totalCount = Math.max(root.path("allRecords").asInt(allRows.size()), allRows.size());
            int flatCount = Math.max(0, totalCount - upCount - downCount);
            LocalDate tradingDay = allRows.stream()
                    .map(MarketDtos.FundRankingResponse::latestNavDate)
                    .filter(day -> day != null)
                    .max(LocalDate::compareTo)
                    .orElse(LocalDate.now(CHINA_ZONE));

            List<MarketDtos.FundRankingResponse> rankings = allRows.stream()
                    .filter(row -> row.returnPct() != null)
                    .sorted((left, right) -> {
                        int pctCompare = right.returnPct().compareTo(left.returnPct());
                        if (pctCompare != 0) {
                            return pctCompare;
                        }
                        return left.fundCode().compareTo(right.fundCode());
                    })
                    .limit(100)
                    .toList();
            rankings = enrichFundSectorNames(rankings);

            return new FundRankSnapshot(
                    new MarketDtos.FundBreadthResponse(
                            tradingDay,
                            LocalDateTime.now(CHINA_ZONE),
                            upCount,
                            downCount,
                            flatCount,
                            totalCount,
                            ratioCount(upCount, totalCount),
                            ratioCount(downCount, totalCount)
                    ),
                    rankings
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private String eastmoneyFundRankingUrl() {
        LocalDate endDate = LocalDate.now(CHINA_ZONE);
        LocalDate startDate = endDate.minusYears(1);
        return EASTMONEY_FUND_RANKING_URL
                + "?op=ph"
                + "&dt=kf"
                + "&ft=all"
                + "&rs="
                + "&gs=0"
                + "&sc=rzdf"
                + "&st=desc"
                + "&sd=" + startDate
                + "&ed=" + endDate
                + "&qdii="
                + "&tabSubtype=,,,,,"
                + "&pi=1"
                + "&pn=20000"
                + "&dx=1"
                + "&v=" + System.currentTimeMillis();
    }

    private MarketDtos.FundRankingResponse parseEastmoneyFundRankRow(String row) {
        if (row == null || row.isBlank()) {
            return null;
        }
        String[] fields = row.contains("|") ? row.split("\\|", -1) : row.split(",", -1);
        if (fields.length < 6) {
            return null;
        }
        String fundCode = fields[0].trim();
        String fundName = fields[1].trim();
        if (!isFundCode(fundCode) || fundName.isBlank()) {
            return null;
        }
        return new MarketDtos.FundRankingResponse(
                fundCode,
                fundName,
                null,
                parseLocalDate(fields[2]),
                decimal(fields[3]),
                decimal(fields[5]),
                "OFFICIAL"
        );
    }

    private List<MarketDtos.FundRankingResponse> enrichFundSectorNames(List<MarketDtos.FundRankingResponse> rows) {
        Map<String, String> sectorNames = fundSectorNames(rows);
        if (sectorNames.isEmpty()) {
            return rows;
        }
        List<MarketDtos.FundRankingResponse> enriched = new ArrayList<>();
        for (MarketDtos.FundRankingResponse row : rows) {
            enriched.add(new MarketDtos.FundRankingResponse(
                    row.fundCode(),
                    row.fundName(),
                    sectorNames.get(row.fundCode()),
                    row.latestNavDate(),
                    row.latestUnitNav(),
                    row.returnPct(),
                    row.dataType()
            ));
        }
        return enriched;
    }

    private Map<String, String> fundSectorNames(List<MarketDtos.FundRankingResponse> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> codes = rows.stream()
                .map(MarketDtos.FundRankingResponse::fundCode)
                .distinct()
                .toList();
        try {
            List<Map.Entry<String, String>> entries = jdbcTemplate.query("""
                    SELECT
                      fsm.fund_code,
                      GROUP_CONCAT(fs.sector_name ORDER BY fs.sector_type, fs.sector_name SEPARATOR ' / ') AS sector_name
                    FROM fund_sector_member fsm
                    JOIN fund_sector fs ON fs.id = fsm.sector_id
                    WHERE fsm.fund_code IN (:codes)
                      AND fs.enabled = 1
                      AND (fsm.effective_to IS NULL OR fsm.effective_to >= CURRENT_DATE)
                    GROUP BY fsm.fund_code
                    """, Map.of("codes", codes), (rs, rowNum) ->
                    Map.entry(rs.getString("fund_code"), rs.getString("sector_name")));
            Map<String, String> sectorNames = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : entries) {
                sectorNames.put(entry.getKey(), entry.getValue());
            }
            return sectorNames;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String normalizeEastmoneyJson(String value) {
        return value.replaceAll("(?<=[{,])\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:", "\"$1\":");
    }

    private List<MarketDtos.IndexQuoteResponse> loadStoredIndexQuotes() {
        return jdbcTemplate.query("""
                SELECT
                  si.market,
                  si.symbol,
                  si.instrument_name,
                  q.trading_day,
                  q.quote_time,
                  q.last_price,
                  q.change_amount,
                  q.change_pct,
                  q.turnover,
                  q.data_lag_seconds
                FROM security_instrument si
                LEFT JOIN market_quote_latest q ON q.instrument_id = si.id
                WHERE si.instrument_type = 'INDEX'
                ORDER BY
                  CASE si.symbol
                    WHEN '000001' THEN 1
                    WHEN '399001' THEN 2
                    WHEN '399006' THEN 3
                    WHEN '000300' THEN 4
                    WHEN '000905' THEN 5
                    WHEN '000688' THEN 6
                    WHEN 'HSI' THEN 7
                    WHEN 'HSCEI' THEN 8
                    WHEN 'HSTECH' THEN 9
                    WHEN 'DJIA' THEN 10
                    WHEN 'SPX' THEN 11
                    WHEN 'NDX100' THEN 12
                    WHEN 'GC' THEN 13
                    WHEN 'AU9999' THEN 14
                    WHEN 'UDI' THEN 15
                    ELSE 99
                  END,
                  si.symbol
                LIMIT 30
                """, (rs, rowNum) -> new MarketDtos.IndexQuoteResponse(
                rs.getString("market"),
                rs.getString("symbol"),
                rs.getString("instrument_name"),
                rs.getBigDecimal("last_price"),
                rs.getBigDecimal("change_amount"),
                rs.getBigDecimal("change_pct"),
                rs.getBigDecimal("turnover"),
                (Integer) rs.getObject("data_lag_seconds"),
                timestamp(rs.getTimestamp("quote_time"))
        ));
    }

    private InstrumentRef upsertInstrument(String market, String symbol, String name, int sourceId) {
        return upsertInstrument(market, symbol, name, sourceId, EASTMONEY_INDEX_QUOTE_URL);
    }

    private InstrumentRef upsertInstrument(String market, String symbol, String name, int sourceId, String sourceUrl) {
        jdbcTemplate.update("""
                INSERT INTO security_instrument (
                  market, symbol, instrument_name, instrument_type, currency, exchange_code,
                  enabled, source_id, source_url, source_updated_at
                ) VALUES (
                  :market, :symbol, :name, 'INDEX', :currency, :market,
                  1, :sourceId, :sourceUrl, CURRENT_TIMESTAMP(3)
                )
                ON DUPLICATE KEY UPDATE
                  instrument_name = VALUES(instrument_name),
                  instrument_type = 'INDEX',
                  enabled = 1,
                  source_id = VALUES(source_id),
                  source_url = VALUES(source_url),
                  source_updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("market", market)
                .addValue("symbol", symbol)
                .addValue("name", name)
                .addValue("currency", currencyForMarket(market, symbol))
                .addValue("sourceId", sourceId)
                .addValue("sourceUrl", sourceUrl));
        return jdbcTemplate.queryForObject("""
                SELECT id, market, symbol, instrument_name
                FROM security_instrument
                WHERE market = :market AND symbol = :symbol
                """, Map.of("market", market, "symbol", symbol), (rs, rowNum) -> new InstrumentRef(
                rs.getLong("id"),
                rs.getString("market"),
                rs.getString("symbol"),
                rs.getString("instrument_name")
        ));
    }

    private void upsertLatestQuote(
            long instrumentId,
            LocalDateTime quoteTime,
            BigDecimal lastPrice,
            BigDecimal prevClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal changeAmount,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover,
            int dataLagSeconds,
            int sourceId
    ) {
        jdbcTemplate.update("""
                INSERT INTO market_quote_latest (
                  instrument_id, trading_day, quote_time, last_price, prev_close, open_price,
                  high_price, low_price, change_amount, change_pct, volume, turnover,
                  data_lag_seconds, source_id
                ) VALUES (
                  :instrumentId, :tradingDay, :quoteTime, :lastPrice, :prevClose, :openPrice,
                  :highPrice, :lowPrice, :changeAmount, :changePct, :volume, :turnover,
                  :dataLagSeconds, :sourceId
                )
                ON DUPLICATE KEY UPDATE
                  trading_day = VALUES(trading_day),
                  quote_time = VALUES(quote_time),
                  last_price = VALUES(last_price),
                  prev_close = VALUES(prev_close),
                  open_price = VALUES(open_price),
                  high_price = VALUES(high_price),
                  low_price = VALUES(low_price),
                  change_amount = VALUES(change_amount),
                  change_pct = VALUES(change_pct),
                  volume = VALUES(volume),
                  turnover = VALUES(turnover),
                  data_lag_seconds = VALUES(data_lag_seconds),
                  source_id = VALUES(source_id),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, quoteParams(
                instrumentId,
                quoteTime.toLocalDate(),
                quoteTime,
                lastPrice,
                prevClose,
                openPrice,
                highPrice,
                lowPrice,
                changeAmount,
                changePct,
                volume,
                turnover,
                dataLagSeconds,
                sourceId
        ));
    }

    private void upsertMinuteQuote(
            long instrumentId,
            LocalDateTime quoteTime,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal prevClose,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover,
            int sourceId
    ) {
        upsertMinuteQuote(
                instrumentId,
                quoteTime.toLocalDate(),
                quoteTime,
                openPrice,
                highPrice,
                lowPrice,
                closePrice,
                prevClose,
                changePct,
                volume,
                turnover,
                sourceId
        );
    }

    private void upsertMinuteQuote(
            long instrumentId,
            LocalDate tradingDay,
            LocalDateTime quoteTime,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal prevClose,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover,
            int sourceId
    ) {
        jdbcTemplate.update("""
                INSERT INTO market_quote_minute (
                  instrument_id, trading_day, quote_time, open_price, high_price, low_price,
                  close_price, prev_close, change_pct, volume, turnover, source_id
                ) VALUES (
                  :instrumentId, :tradingDay, :quoteTime, :openPrice, :highPrice, :lowPrice,
                  :lastPrice, :prevClose, :changePct, :volume, :turnover, :sourceId
                )
                ON DUPLICATE KEY UPDATE
                  open_price = VALUES(open_price),
                  high_price = VALUES(high_price),
                  low_price = VALUES(low_price),
                  close_price = VALUES(close_price),
                  prev_close = VALUES(prev_close),
                  change_pct = VALUES(change_pct),
                  volume = VALUES(volume),
                  turnover = VALUES(turnover),
                  source_id = VALUES(source_id)
                """, quoteParams(
                instrumentId,
                tradingDay,
                quoteTime,
                closePrice,
                prevClose,
                openPrice,
                highPrice,
                lowPrice,
                null,
                changePct,
                volume,
                turnover,
                0,
                sourceId
        ));
    }

    private MapSqlParameterSource quoteParams(
            long instrumentId,
            LocalDate tradingDay,
            LocalDateTime quoteTime,
            BigDecimal lastPrice,
            BigDecimal prevClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal changeAmount,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover,
            int dataLagSeconds,
            int sourceId
    ) {
        return new MapSqlParameterSource()
                .addValue("instrumentId", instrumentId)
                .addValue("tradingDay", tradingDay)
                .addValue("quoteTime", quoteTime)
                .addValue("lastPrice", lastPrice)
                .addValue("prevClose", prevClose)
                .addValue("openPrice", openPrice)
                .addValue("highPrice", highPrice)
                .addValue("lowPrice", lowPrice)
                .addValue("changeAmount", changeAmount)
                .addValue("changePct", changePct)
                .addValue("volume", volume)
                .addValue("turnover", turnover)
                .addValue("dataLagSeconds", dataLagSeconds)
                .addValue("sourceId", sourceId);
    }

    private int ensureTencentDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = 'TENCENT_INDEX_QUOTE' LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'TENCENT_INDEX_QUOTE', '鑵捐鎸囨暟琛屾儏', 'THIRD_PARTY', 2, 'UNKNOWN',
                  'https://qt.gtimg.cn/', 40, 1, '鍏紑缃戠粶鎺ュ彛锛岀敤浜庡ぇ鐩樻寚鏁板疄鏃跺睍绀?
                )
                """, new MapSqlParameterSource(), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private int ensureEastmoneyIndexDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = 'EASTMONEY_INDEX_QUOTE' LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'EASTMONEY_INDEX_QUOTE', 'Eastmoney index quote', 'THIRD_PARTY', 2, 'UNKNOWN',
                  'https://push2his.eastmoney.com/', 41, 1, 'Public network API for index intraday and daily charts'
                )
                """, new MapSqlParameterSource(), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private int ensureEastmoneyFuturesDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = 'EASTMONEY_FUTURES_QUOTE' LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'EASTMONEY_FUTURES_QUOTE', 'Eastmoney futures quote', 'THIRD_PARTY', 3, 'UNKNOWN',
                  'https://futsseapi.eastmoney.com/', 39, 1, 'Realtime futures quote and tick feed used for gold main contract fallback'
                )
                """, new MapSqlParameterSource(), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private int ensureShfeOfficialDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = 'SHFE_OFFICIAL_DAILY' LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'SHFE_OFFICIAL_DAILY', 'SHFE official daily data', 'EXCHANGE', 1, 'PUBLIC',
                  'https://www.shfe.cn/', 20, 1, 'Official SHFE daily trading report for gold main contract history and close snapshot'
                )
                """, new MapSqlParameterSource(), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private int ensureSgeOfficialDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = 'SGE_OFFICIAL_DELAYED_15M' LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'SGE_OFFICIAL_DELAYED_15M', 'SGE official delayed gold quote', 'EXCHANGE', 1, 'PUBLIC',
                  'https://www.sge.com.cn/', 19, 1, 'Official SGE delayed quote with corrected quote timestamp for Au99.99'
                )
                """, new MapSqlParameterSource(), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private int ensureSinaMarketDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = 'SINA_MARKET_QUOTE' LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'SINA_MARKET_QUOTE', 'Sina market quote', 'THIRD_PARTY', 2, 'UNKNOWN',
                  'https://finance.sina.com.cn/', 42, 1, 'Fallback source for HK, US and gold market quotes and available charts'
                )
                """, new MapSqlParameterSource(), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private int ensureYahooMarketDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id FROM data_source WHERE source_code = 'YAHOO_MARKET_CHART' LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'YAHOO_MARKET_CHART', 'Yahoo market chart', 'THIRD_PARTY', 3, 'UNKNOWN',
                  'https://query1.finance.yahoo.com/', 43, 1, 'Fallback chart source for US, HK and spot gold intraday or daily series'
                )
                """, new MapSqlParameterSource(), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 1 : key.intValue();
    }

    private String eastmoneyTrendUrl(DefaultIndex index) {
        return EASTMONEY_TRENDS_URL
                + "?secid=" + secid(index)
                + "&fields1=f1,f2,f3,f4,f5,f6,f7,f8,f9,f10,f11"
                + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                + "&ut=fa5fd1943c7b386f172d6893dbfba10b"
                + "&fltt=2&invt=2"
                + "&ndays=1&iscr=0&iscca=0"
                + "&_=" + System.currentTimeMillis();
    }

    private LocalDateTime normalizeEastmoneyTrendTime(DefaultIndex index, LocalDateTime quoteTime) {
        return quoteTime;
    }

    private String eastmoneyKlineUrl(DefaultIndex index, LocalDate startDate, LocalDate endDate) {
        return eastmoneyKlineUrl(index, startDate, endDate, 0);
    }

    private String eastmoneyKlineUrl(DefaultIndex index, LocalDate startDate, LocalDate endDate, int fqt) {
        return EASTMONEY_KLINE_URL
                + "?secid=" + secid(index)
                + "&fields1=f1,f2,f3,f4,f5,f6"
                + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                + "&ut=fa5fd1943c7b386f172d6893dbfba10b"
                + "&fltt=2&invt=2"
                + "&klt=101&fqt=" + fqt
                + "&rtntype=6"
                + "&lmt=1000000"
                + "&beg=" + startDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "&end=" + endDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "&_=" + System.currentTimeMillis();
    }

    private String secid(DefaultIndex index) {
        if (index.eastmoneySecid() != null && !index.eastmoneySecid().isBlank()) {
            return index.eastmoneySecid();
        }
        return ("SSE".equals(index.market()) ? "1." : "0.") + index.symbol();
    }

    private List<MarketDtos.HistoryPointResponse> fetchEastmoneyKline(String symbol, LocalDate startDate, LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        if (index == null) {
            return List.of();
        }
        List<MarketDtos.HistoryPointResponse> points = fetchEastmoneyKline(index, startDate, endDate, 0);
        if (points.isEmpty()) {
            points = fetchEastmoneyKline(index, startDate, endDate, 1);
        }
        return points;
    }

    private List<MarketDtos.HistoryPointResponse> fetchEastmoneyKline(DefaultIndex index, LocalDate startDate, LocalDate endDate, int fqt) {
        try {
            JsonNode klines = objectMapper.readTree(fetchUtf8Text(eastmoneyKlineUrl(index, startDate, endDate, fqt)))
                    .path("data")
                    .path("klines");
            if (!klines.isArray()) {
                return List.of();
            }
            List<MarketDtos.HistoryPointResponse> points = new ArrayList<>();
            for (JsonNode item : klines) {
                String[] fields = item.asText("").split(",", -1);
                if (fields.length < 5) {
                    continue;
                }
                points.add(new MarketDtos.HistoryPointResponse(
                        LocalDate.parse(fields[0]),
                        decimal(fields[1]),
                        decimal(fields[2]),
                        decimal(fields[3]),
                        decimal(fields[4]),
                        decimalField(fields, 8),
                        decimalField(fields, 5),
                        decimalField(fields, 6)
                ));
            }
            return points;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.HistoryPointResponse> fetchTencentKline(String symbol, LocalDate startDate, LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        if (index == null) {
            return List.of();
        }
        try {
            String code = index.tencentCode();
            if (code == null || code.isBlank()) {
                return List.of();
            }
            long days = Math.max(40, ChronoUnit.DAYS.between(startDate, endDate) + 30);
            String url = TENCENT_INDEX_KLINE_URL
                    + "?param=" + code + ",day,,," + days + ",qfq";
            JsonNode root = objectMapper.readTree(fetchUtf8Text(url));
            JsonNode rows = root.path("data").path(code).path("day");
            if (!rows.isArray()) {
                rows = root.path("data").path(code).path("qfqday");
            }
            if (!rows.isArray()) {
                return List.of();
            }
            List<MarketDtos.HistoryPointResponse> points = new ArrayList<>();
            BigDecimal previousClose = null;
            for (JsonNode row : rows) {
                if (!row.isArray() || row.size() < 6) {
                    continue;
                }
                LocalDate tradingDay = LocalDate.parse(row.get(0).asText());
                BigDecimal closePrice = decimal(row.get(2).asText(null));
                if (closePrice == null) {
                    continue;
                }
                if (tradingDay.isBefore(startDate) || tradingDay.isAfter(endDate)) {
                    previousClose = closePrice;
                    continue;
                }
                points.add(new MarketDtos.HistoryPointResponse(
                        tradingDay,
                        decimal(row.get(1).asText(null)),
                        closePrice,
                        decimal(row.get(3).asText(null)),
                        decimal(row.get(4).asText(null)),
                        ratioPct(closePrice, previousClose),
                        "HKEX".equals(index.market()) ? null : decimal(row.get(5).asText(null)),
                        "HKEX".equals(index.market()) ? decimal(row.get(5).asText(null)) : null
                ));
                previousClose = closePrice;
            }
            return points;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.HistoryPointResponse> fetchSinaDailyKline(String symbol, LocalDate startDate, LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        String code = sinaDailyKlineSymbol(index);
        if (code == null || code.isBlank()) {
            return List.of();
        }
        try {
            String url = SINA_US_DAILY_URL + "?symbol=" + urlEncode(code);
            String body = fetchSinaText(url, StandardCharsets.UTF_8);
            String json = extractJsonArray(body);
            if (json.isBlank()) {
                return List.of();
            }
            JsonNode rows = objectMapper.readTree(json);
            if (!rows.isArray()) {
                return List.of();
            }
            List<MarketDtos.HistoryPointResponse> points = new ArrayList<>();
            BigDecimal previousClose = null;
            for (JsonNode row : rows) {
                LocalDate tradingDay = parseLocalDate(row.path("d").asText(null));
                BigDecimal closePrice = decimal(row.path("c").asText(null));
                if (tradingDay == null || closePrice == null) {
                    continue;
                }
                BigDecimal changePct = ratioPct(closePrice, previousClose);
                if (!tradingDay.isBefore(startDate) && !tradingDay.isAfter(endDate)) {
                    points.add(new MarketDtos.HistoryPointResponse(
                            tradingDay,
                            decimal(row.path("o").asText(null)),
                            closePrice,
                            decimal(row.path("h").asText(null)),
                            decimal(row.path("l").asText(null)),
                            changePct,
                            decimal(row.path("v").asText(null)),
                            decimal(row.path("a").asText(null))
                    ));
                }
                previousClose = closePrice;
            }
            return points;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.HistoryPointResponse> fetchSinaFutureDailyKline(String symbol, LocalDate startDate, LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        String code = sinaFutureSymbol(index);
        if (code == null || code.isBlank()) {
            return List.of();
        }
        try {
            String url = SINA_FUTURES_DAILY_URL + "?symbol=" + urlEncode(code);
            JsonNode rows = firstDataArray(objectMapper.readTree(fetchSinaText(url, StandardCharsets.UTF_8)));
            if (rows == null || !rows.isArray()) {
                return List.of();
            }
            List<MarketDtos.HistoryPointResponse> points = new ArrayList<>();
            BigDecimal previousClose = null;
            for (JsonNode row : rows) {
                LocalDate tradingDay = sinaRowDate(row);
                BigDecimal closePrice = rowDecimal(row, "c", "close", 4);
                if (tradingDay == null || closePrice == null) {
                    continue;
                }
                BigDecimal changePct = ratioPct(closePrice, previousClose);
                if (!tradingDay.isBefore(startDate) && !tradingDay.isAfter(endDate)) {
                    points.add(new MarketDtos.HistoryPointResponse(
                            tradingDay,
                            rowDecimal(row, "o", "open", 1),
                            closePrice,
                            rowDecimal(row, "h", "high", 2),
                            rowDecimal(row, "l", "low", 3),
                            changePct,
                            rowDecimal(row, "v", "volume", 5),
                            null
                    ));
                }
                previousClose = closePrice;
            }
            return points;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.HistoryPointResponse> fetchSinaGlobalFutureDailyKline(String symbol,
                                                                                  LocalDate startDate,
                                                                                  LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        String code = sinaGlobalFutureSymbol(index);
        if (code == null || code.isBlank()) {
            return List.of();
        }
        try {
            String url = SINA_GLOBAL_FUTURES_DAILY_URL + "?symbol=" + urlEncode(code);
            JsonNode rows = objectMapper.readTree(fetchSinaText(url, StandardCharsets.UTF_8));
            if (!rows.isArray()) {
                return List.of();
            }
            List<MarketDtos.HistoryPointResponse> points = new ArrayList<>();
            BigDecimal previousClose = null;
            for (JsonNode row : rows) {
                LocalDate tradingDay = parseLocalDate(row.path("date").asText(null));
                BigDecimal closePrice = decimal(row.path("close").asText(null));
                if (tradingDay == null || closePrice == null) {
                    continue;
                }
                BigDecimal changePct = ratioPct(closePrice, previousClose);
                if (!tradingDay.isBefore(startDate) && !tradingDay.isAfter(endDate)) {
                    points.add(new MarketDtos.HistoryPointResponse(
                            tradingDay,
                            decimal(row.path("open").asText(null)),
                            closePrice,
                            decimal(row.path("high").asText(null)),
                            decimal(row.path("low").asText(null)),
                            changePct,
                            decimal(row.path("volume").asText(null)),
                            null
                    ));
                }
                previousClose = closePrice;
            }
            return points;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.HistoryPointResponse> fetchShfeGoldMainDailyKline(String symbol, LocalDate startDate, LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        if (index == null || !"AUM".equals(index.symbol())) {
            return List.of();
        }
        try {
            ensureShfeOfficialDataSource();
        } catch (Exception ignored) {
        }
        List<MarketDtos.HistoryPointResponse> points = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            if (!isWeekend(cursor)) {
                MarketDtos.HistoryPointResponse point = fetchShfeGoldMainDailyBar(cursor);
                if (point != null) {
                    points.add(point);
                }
            }
            cursor = cursor.plusDays(1);
        }
        return points;
    }

    private MarketDtos.HistoryPointResponse fetchShfeGoldMainDailyBar(LocalDate tradingDay) {
        try {
            String url = String.format(SHFE_DAILY_KX_URL, tradingDay.format(DateTimeFormatter.BASIC_ISO_DATE));
            JsonNode rows = objectMapper.readTree(fetchUtf8Text(url)).path("o_curinstrument");
            if (!rows.isArray()) {
                return null;
            }
            JsonNode best = null;
            BigDecimal bestVolume = null;
            BigDecimal bestOpenInterest = null;
            for (JsonNode row : rows) {
                if (!isShfeGoldFutureRow(row)) {
                    continue;
                }
                BigDecimal closePrice = decimal(row.path("CLOSEPRICE").asText(null));
                BigDecimal volume = decimal(row.path("VOLUME").asText(null));
                if (closePrice == null || volume == null || volume.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                BigDecimal openInterest = decimal(row.path("OPENINTEREST").asText(null));
                boolean better = best == null
                        || volume.compareTo(bestVolume) > 0
                        || (volume.compareTo(bestVolume) == 0 && openInterest != null && bestOpenInterest != null
                        && openInterest.compareTo(bestOpenInterest) > 0);
                if (better) {
                    best = row;
                    bestVolume = volume;
                    bestOpenInterest = openInterest;
                }
            }
            if (best == null) {
                return null;
            }
            BigDecimal closePrice = decimal(best.path("CLOSEPRICE").asText(null));
            BigDecimal previousSettlement = decimal(best.path("PRESETTLEMENTPRICE").asText(null));
            return new MarketDtos.HistoryPointResponse(
                    tradingDay,
                    decimal(best.path("OPENPRICE").asText(null)),
                    closePrice,
                    decimal(best.path("HIGHESTPRICE").asText(null)),
                    decimal(best.path("LOWESTPRICE").asText(null)),
                    ratioPct(closePrice, previousSettlement),
                    decimal(best.path("VOLUME").asText(null)),
                    decimal(best.path("TURNOVER").asText(null))
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean isShfeGoldFutureRow(JsonNode row) {
        if (row == null || row.isMissingNode() || row.isNull()) {
            return false;
        }
        String productId = row.path("PRODUCTID").asText("");
        String productGroupId = row.path("PRODUCTGROUPID").asText("");
        String deliveryMonth = row.path("DELIVERYMONTH").asText("");
        return ("au_f".equalsIgnoreCase(productId) || "au".equalsIgnoreCase(productGroupId))
                && deliveryMonth != null
                && !deliveryMonth.isBlank()
                && deliveryMonth.chars().allMatch(Character::isDigit);
    }

    private List<MarketDtos.HistoryPointResponse> fetchSgeSpotGoldDailyKline(String symbol, LocalDate startDate, LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        if (index == null || !"AU9999".equals(index.symbol()) || startDate == null || endDate == null || startDate.isAfter(endDate)) {
            return List.of();
        }
        try {
            ensureSgeOfficialDataSource();
        } catch (Exception exception) {
            return List.of();
        }

        Map<LocalDate, MarketDtos.HistoryPointResponse> byDay = new LinkedHashMap<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            LocalDate candidateEnd = cursor.plusDays(SGE_DAILY_CHUNK_DAYS - 1L);
            LocalDate chunkEnd = candidateEnd.isAfter(endDate) ? endDate : candidateEnd;
            try {
                String url = String.format(SGE_DAILY_QUOTE_URL, cursor, chunkEnd);
                String text = normalizeHtmlText(fetchUtf8Text(url));
                Matcher matcher = SGE_DAILY_AU9999_PATTERN.matcher(text);
                while (matcher.find()) {
                    LocalDate tradingDay = parseLocalDate(matcher.group(1));
                    if (tradingDay == null || tradingDay.isBefore(startDate) || tradingDay.isAfter(endDate)) {
                        continue;
                    }
                    byDay.put(tradingDay, new MarketDtos.HistoryPointResponse(
                            tradingDay,
                            decimal(matcher.group(2)),
                            decimal(matcher.group(5)),
                            decimal(matcher.group(3)),
                            decimal(matcher.group(4)),
                            decimal(matcher.group(7)),
                            decimal(matcher.group(9)),
                            decimal(matcher.group(10))
                    ));
                }
            } catch (Exception ignored) {
                // Keep successful official chunks and let stored data fill isolated gaps.
            }
            cursor = chunkEnd.plusDays(1);
        }
        return byDay.values().stream()
                .sorted((left, right) -> left.tradingDay().compareTo(right.tradingDay()))
                .toList();
    }

    private MarketDtos.HistoryPointResponse fetchSgeSpotGoldDailyBar(LocalDate tradingDay) {
        List<MarketDtos.HistoryPointResponse> points = fetchSgeSpotGoldDailyKline("AU9999", tradingDay, tradingDay);
        return points.isEmpty() ? null : points.get(0);
    }

    private List<MarketDtos.HistoryPointResponse> fetchYahooDailyKline(String symbol, LocalDate startDate, LocalDate endDate) {
        DefaultIndex index = defaultIndex(symbol);
        String code = yahooChartSymbol(index);
        if (code == null || code.isBlank()) {
            return List.of();
        }
        try {
            String url = yahooDailyChartUrl(code, startDate, endDate);
            JsonNode result = yahooChartResult(url);
            if (result == null) {
                return List.of();
            }
            JsonNode timestamps = result.path("timestamp");
            JsonNode quote = result.path("indicators").path("quote").path(0);
            if (!timestamps.isArray() || quote.isMissingNode()) {
                return List.of();
            }
            List<MarketDtos.HistoryPointResponse> points = new ArrayList<>();
            BigDecimal previousClose = firstDecimal(
                    result.path("meta").path("chartPreviousClose").asText(null),
                    result.path("meta").path("previousClose").asText(null)
            );
            for (int i = 0; i < timestamps.size(); i += 1) {
                Long epochSeconds = longValue(timestamps.get(i));
                BigDecimal closePrice = decimalAt(quote.path("close"), i);
                if (epochSeconds == null || closePrice == null) {
                    continue;
                }
                LocalDate tradingDay = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), CHINA_ZONE).toLocalDate();
                BigDecimal changePct = ratioPct(closePrice, previousClose);
                if (!tradingDay.isBefore(startDate) && !tradingDay.isAfter(endDate)) {
                    points.add(new MarketDtos.HistoryPointResponse(
                            tradingDay,
                            decimalAt(quote.path("open"), i),
                            closePrice,
                            decimalAt(quote.path("high"), i),
                            decimalAt(quote.path("low"), i),
                            changePct,
                            decimalAt(quote.path("volume"), i),
                            null
                    ));
                }
                previousClose = closePrice;
            }
            return points;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<MarketDtos.HistoryPointResponse> loadStoredDailyHistory(String symbol, LocalDate startDate, LocalDate endDate) {
        List<InstrumentRef> instruments = findIndexInstruments(symbol);
        if (instruments.isEmpty()) {
            return List.of();
        }

        DefaultIndex defaultIndex = defaultIndex(symbol);
        String timeFilter = defaultIndex == null || isChinaMarket(defaultIndex)
                ? "AND TIME(quote_time) BETWEEN '09:30:00' AND '15:00:00'"
                : "";
        List<StoredMinutePoint> rows = jdbcTemplate.query("""
                SELECT trading_day,
                       quote_time,
                       open_price,
                       high_price,
                       low_price,
                       COALESCE(close_price, open_price) AS close_price,
                       change_pct,
                       volume,
                       turnover
                FROM market_quote_minute
                WHERE instrument_id = :instrumentId
                  AND trading_day BETWEEN :startDate AND :endDate
                  AND (change_pct IS NULL OR change_pct BETWEEN -100 AND 100)
                  %s
                ORDER BY trading_day, quote_time
                """.formatted(timeFilter), new MapSqlParameterSource()
                .addValue("instrumentId", instruments.get(0).id())
                .addValue("startDate", startDate)
                .addValue("endDate", endDate), (rs, rowNum) -> new StoredMinutePoint(
                localDate(rs.getDate("trading_day")),
                timestamp(rs.getTimestamp("quote_time")),
                rs.getBigDecimal("open_price"),
                rs.getBigDecimal("high_price"),
                rs.getBigDecimal("low_price"),
                rs.getBigDecimal("close_price"),
                rs.getBigDecimal("change_pct"),
                rs.getBigDecimal("volume"),
                rs.getBigDecimal("turnover")
        ));

        Map<LocalDate, StoredDailyBar> bars = new LinkedHashMap<>();
        for (StoredMinutePoint row : rows) {
            bars.computeIfAbsent(row.tradingDay(), StoredDailyBar::new).add(row);
        }
        return bars.values().stream()
                .map(StoredDailyBar::toResponse)
                .toList();
    }

    private List<InstrumentRef> findIndexInstruments(String symbol) {
        return jdbcTemplate.query("""
                SELECT id, market, symbol, instrument_name
                FROM security_instrument
                WHERE instrument_type = 'INDEX' AND symbol = :symbol AND enabled = 1
                ORDER BY
                  CASE market
                    WHEN 'SSE' THEN 1
                    WHEN 'SZSE' THEN 2
                    WHEN 'INDEX' THEN 3
                    ELSE 9
                  END
                LIMIT 1
                """, Map.of("symbol", symbol), (rs, rowNum) -> new InstrumentRef(
                rs.getLong("id"),
                rs.getString("market"),
                rs.getString("symbol"),
                rs.getString("instrument_name")
        ));
    }

    private List<MarketDtos.MinutePointResponse> loadMinutePoints(long instrumentId, LocalDate tradingDay) {
        return loadMinutePoints(instrumentId, tradingDay, null);
    }

    private List<MarketDtos.MinutePointResponse> loadMinutePoints(long instrumentId, LocalDate tradingDay, DefaultIndex index) {
        if (isHkMarket(index)) {
            List<MarketDtos.MinutePointResponse> eastmoneyPoints = loadMinutePoints(
                    instrumentId,
                    tradingDay,
                    index,
                    "EASTMONEY_INDEX_QUOTE"
            );
            List<MarketDtos.MinutePointResponse> sinaPoints = loadMinutePoints(
                    instrumentId,
                    tradingDay,
                    index,
                    "SINA_MARKET_QUOTE"
            );
            return selectHkMinutePoints(eastmoneyPoints, sinaPoints);
        }
        return loadMinutePoints(instrumentId, tradingDay, index, null);
    }

    private List<MarketDtos.MinutePointResponse> selectHkMinutePoints(List<MarketDtos.MinutePointResponse> eastmoneyPoints,
                                                                      List<MarketDtos.MinutePointResponse> sinaPoints) {
        if (eastmoneyPoints == null || eastmoneyPoints.isEmpty()) {
            return sinaPoints == null ? List.of() : sinaPoints;
        }
        if (sinaPoints == null || sinaPoints.isEmpty()) {
            return eastmoneyPoints;
        }
        if (sinaPoints.size() > eastmoneyPoints.size() && hkSeriesAligned(eastmoneyPoints, sinaPoints)) {
            return sinaPoints;
        }
        return eastmoneyPoints;
    }

    private boolean hkSeriesAligned(List<MarketDtos.MinutePointResponse> left,
                                    List<MarketDtos.MinutePointResponse> right) {
        MarketDtos.MinutePointResponse leftLast = left.get(left.size() - 1);
        MarketDtos.MinutePointResponse rightLast = right.get(right.size() - 1);
        if (leftLast.quoteTime() == null || rightLast.quoteTime() == null
                || leftLast.price() == null || rightLast.price() == null
                || leftLast.price().compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        long minuteGap = Math.abs(Duration.between(leftLast.quoteTime(), rightLast.quoteTime()).toMinutes());
        BigDecimal priceGapPct = leftLast.price()
                .subtract(rightLast.price())
                .abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(leftLast.price().abs(), 6, RoundingMode.HALF_UP);
        return minuteGap <= 5 && priceGapPct.compareTo(BigDecimal.valueOf(0.2)) <= 0;
    }

    private List<MarketDtos.MinutePointResponse> loadMinutePoints(long instrumentId,
                                                                  LocalDate tradingDay,
                                                                  DefaultIndex index,
                                                                  String sourceCode) {
        String sessionFilter = minuteSessionFilter(index);
        String sourceFilter = sourceCode == null
                ? ""
                : "AND source_id = (SELECT id FROM data_source WHERE source_code = :sourceCode LIMIT 1)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("instrumentId", instrumentId)
                .addValue("tradingDay", tradingDay);
        if (sourceCode != null) {
            params.addValue("sourceCode", sourceCode);
        }
        if (isUsMarket(index)) {
            params.addValue("sessionStart", usSessionStartInChina(tradingDay));
            params.addValue("sessionEnd", usSessionEndInChina(tradingDay));
        } else if (isOvernightSpotMarket(index)) {
            params.addValue("sessionStart", tradingDay.atTime(6, 0));
            params.addValue("sessionEnd", tradingDay.plusDays(1).atTime(5, 0));
        }
        List<MarketDtos.MinutePointResponse> points = jdbcTemplate.query("""
                SELECT mq.quote_time,
                       COALESCE(mq.close_price, mq.open_price) AS price,
                       mq.change_pct,
                       mq.volume,
                       mq.turnover
                FROM market_quote_minute mq
                JOIN (
                  SELECT MAX(quote_time) AS quote_time
                  FROM market_quote_minute
                  WHERE instrument_id = :instrumentId
                    AND (change_pct IS NULL OR change_pct BETWEEN -100 AND 100)
                    %s
                    %s
                  GROUP BY DATE_FORMAT(quote_time, '%%Y-%%m-%%d %%H:%%i')
                ) latest ON latest.quote_time = mq.quote_time
                WHERE mq.instrument_id = :instrumentId
                  AND (mq.change_pct IS NULL OR mq.change_pct BETWEEN -100 AND 100)
                ORDER BY mq.quote_time
                """.formatted(sessionFilter, sourceFilter), params, (rs, rowNum) ->
                new MarketDtos.MinutePointResponse(
                        timestamp(rs.getTimestamp("quote_time")),
                        rs.getBigDecimal("price"),
                        rs.getBigDecimal("change_pct"),
                        rs.getBigDecimal("volume"),
                        rs.getBigDecimal("turnover")
                ));
        return sanitizeMinutePoints(points, index, tradingDay);
    }

    private String minuteSessionFilter(DefaultIndex index) {
        if (isUsMarket(index)) {
            return "AND quote_time BETWEEN :sessionStart AND :sessionEnd";
        }
        if (isOvernightSpotMarket(index)) {
            return "AND quote_time BETWEEN :sessionStart AND :sessionEnd";
        }
        if (isHkMarket(index)) {
            return """
                    AND trading_day = :tradingDay
                    AND (
                      TIME(quote_time) BETWEEN '09:30:00' AND '12:00:00'
                      OR TIME(quote_time) BETWEEN '13:00:00' AND '16:00:00'
                    )
                    """;
        }
        if (index == null || isChinaMarket(index)) {
            return """
                    AND trading_day = :tradingDay
                    AND (
                      TIME(quote_time) BETWEEN '09:30:00' AND '11:30:00'
                      OR TIME(quote_time) BETWEEN '13:00:00' AND '15:00:00'
                    )
                    """;
        }
        return "AND trading_day = :tradingDay";
    }

    private List<MarketDtos.MinutePointResponse> sanitizeMinutePoints(List<MarketDtos.MinutePointResponse> points,
                                                                      DefaultIndex index,
                                                                      LocalDate tradingDay) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        Map<LocalDateTime, MarketDtos.MinutePointResponse> byMinute = new LinkedHashMap<>();
        points.stream()
                .filter(point -> point != null && point.quoteTime() != null && point.price() != null)
                .filter(point -> !isFutureQuoteTime(point.quoteTime()))
                .filter(point -> isReasonableChange(point.changePct()))
                .filter(point -> belongsToMinuteSession(index, tradingDay, point.quoteTime()))
                .sorted((left, right) -> left.quoteTime().compareTo(right.quoteTime()))
                .forEach(point -> byMinute.put(point.quoteTime().truncatedTo(ChronoUnit.MINUTES), point));
        return new ArrayList<>(byMinute.values());
    }

    private boolean isReasonableChange(BigDecimal changePct) {
        return changePct == null
                || (changePct.compareTo(BigDecimal.valueOf(-100)) >= 0 && changePct.compareTo(BigDecimal.valueOf(100)) <= 0);
    }

    private boolean belongsToMinuteSession(DefaultIndex index, LocalDate tradingDay, LocalDateTime quoteTime) {
        if (tradingDay == null || quoteTime == null) {
            return true;
        }
        if (isUsMarket(index)) {
            LocalDateTime sessionStart = usSessionStartInChina(tradingDay);
            LocalDateTime sessionEnd = usSessionEndInChina(tradingDay);
            return !quoteTime.isBefore(sessionStart) && !quoteTime.isAfter(sessionEnd);
        }
        if (isHkMarket(index)) {
            LocalTime time = quoteTime.toLocalTime();
            boolean morning = !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.NOON);
            boolean afternoon = !time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(16, 0));
            return tradingDay.equals(quoteTime.toLocalDate()) && (morning || afternoon);
        }
        if (isSgeMarket(index)) {
            LocalTime time = quoteTime.toLocalTime();
            boolean night = !time.isBefore(LocalTime.of(20, 0)) || !time.isAfter(LocalTime.of(2, 30));
            boolean morning = !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(11, 30));
            boolean afternoon = !time.isBefore(LocalTime.of(13, 30)) && !time.isAfter(LocalTime.of(15, 30));
            return tradingDay.equals(marketTradingDay(index, quoteTime)) && (night || morning || afternoon);
        }
        if (isOvernightSpotMarket(index)) {
            LocalDateTime sessionStart = tradingDay.atTime(6, 0);
            LocalDateTime sessionEnd = tradingDay.plusDays(1).atTime(5, 0);
            return !quoteTime.isBefore(sessionStart) && !quoteTime.isAfter(sessionEnd);
        }
        if (isShfeMarket(index)) {
            LocalTime time = quoteTime.toLocalTime();
            boolean night = !time.isBefore(LocalTime.of(21, 0)) || !time.isAfter(LocalTime.of(2, 30));
            boolean morning = !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(11, 30));
            boolean afternoon = !time.isBefore(LocalTime.of(13, 30)) && !time.isAfter(LocalTime.of(15, 0));
            return tradingDay.equals(marketTradingDay(index, quoteTime)) && (night || morning || afternoon);
        }
        if (index == null || isChinaMarket(index)) {
            LocalTime time = quoteTime.toLocalTime();
            boolean morning = !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30));
            boolean afternoon = !time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0));
            return tradingDay.equals(quoteTime.toLocalDate()) && (morning || afternoon);
        }
        return tradingDay.equals(marketTradingDay(index, quoteTime));
    }

    private List<MarketDtos.MinutePointResponse> loadLatestQuotePoint(long instrumentId) {
        return jdbcTemplate.query("""
                       SELECT quote_time,
                       last_price,
                       change_pct,
                       volume,
                       turnover
                FROM market_quote_latest
                WHERE instrument_id = :instrumentId
                  AND last_price IS NOT NULL
                  AND quote_time <= :maxQuoteTime
                ORDER BY quote_time DESC
                LIMIT 1
                """, Map.of(
                "instrumentId", instrumentId,
                "maxQuoteTime", LocalDateTime.now(CHINA_ZONE).plusMinutes(2)
        ), (rs, rowNum) ->
                new MarketDtos.MinutePointResponse(
                        timestamp(rs.getTimestamp("quote_time")),
                        rs.getBigDecimal("last_price"),
                        rs.getBigDecimal("change_pct"),
                        rs.getBigDecimal("volume"),
                        rs.getBigDecimal("turnover")
                ));
    }

    private String normalizeHtmlText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?i)</tr>|</p>|<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#37;", "%")
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private LocalDate parseSgeReportDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = SGE_REPORT_DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private String fetchUtf8Text(String url) {
        byte[] bytes;
        try {
            bytes = restClient.get().uri(url).retrieve().body(byte[].class);
        } catch (Exception exception) {
            String fallbackUrl = eastmoneyHttpFallbackUrl(url);
            if (fallbackUrl.equals(url)) {
                throw exception;
            }
            bytes = restClient.get().uri(fallbackUrl).retrieve().body(byte[].class);
        }
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String eastmoneyHttpFallbackUrl(String url) {
        if (url == null) {
            return "";
        }
        if (url.startsWith("https://push2.eastmoney.com/") || url.startsWith("https://push2his.eastmoney.com/")) {
            return "http://" + url.substring("https://".length());
        }
        return url;
    }

    private String fetchGbkText(String url) {
        byte[] bytes = restClient.get().uri(url).retrieve().body(byte[].class);
        return bytes == null ? "" : new String(bytes, GBK);
    }

    private String fetchSinaText(String url, Charset charset) {
        byte[] bytes = restClient.get()
                .uri(url)
                .header("Referer", SINA_REFERER)
                .header(HttpHeaders.ACCEPT, "*/*")
                .retrieve()
                .body(byte[].class);
        return bytes == null ? "" : new String(bytes, charset);
    }

    private BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim()
                .replace("%", "")
                .replace(",", "")
                .replace("\u00A0", "");
        if (normalized.isBlank() || "--".equals(normalized) || "-".equals(normalized)) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimalField(String[] fields, int index) {
        return fields.length > index ? decimal(fields[index]) : null;
    }

    private BigDecimal firstDecimalField(String[] fields, int... indexes) {
        for (int index : indexes) {
            BigDecimal value = decimalField(fields, index);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal firstDecimal(String... values) {
        for (String value : values) {
            BigDecimal decimal = decimal(value);
            if (decimal != null) {
                return decimal;
            }
        }
        return null;
    }

    private BigDecimal decimalAt(JsonNode node, int index) {
        if (node == null || !node.isArray() || index < 0 || index >= node.size()) {
            return null;
        }
        JsonNode value = node.get(index);
        return value == null || value.isNull() ? null : decimal(value.asText(null));
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return node.asLong();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String textField(String[] fields, int index, String fallback) {
        if (fields.length <= index) {
            return fallback;
        }
        String value = fields[index] == null ? "" : fields[index].trim();
        return value.isBlank() || "--".equals(value) ? fallback : value;
    }

    private LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank() || "--".equals(value.trim())) {
            return null;
        }
        String normalized = value.trim();
        try {
            if (normalized.contains("/")) {
                return LocalDate.parse(normalized, SINA_SLASH_DATE_FORMATTER);
            }
            return LocalDate.parse(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDateTime parseSinaQuoteTime(String[] fields) {
        LocalDate date = null;
        LocalTime time = null;
        for (int i = fields.length - 1; i >= 0; i--) {
            String value = fields[i] == null ? "" : fields[i].trim();
            LocalDateTime dateTime = parseSinaDateTime(value);
            if (dateTime != null) {
                return dateTime;
            }
            if (date == null) {
                date = parseLocalDate(value);
            }
            if (time == null) {
                time = parseSinaTime(value);
            }
            if (date != null && time != null) {
                return date.atTime(time);
            }
        }
        return LocalDateTime.now(CHINA_ZONE);
    }

    private LocalDateTime parseSinaDateTime(String value) {
        if (value == null || value.isBlank() || "--".equals(value.trim())) {
            return null;
        }
        String normalized = value.trim().replace('/', '-');
        try {
            if (normalized.length() >= 19 && normalized.charAt(4) == '-') {
                return LocalDateTime.parse(normalized.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            if (normalized.length() >= 16 && normalized.charAt(4) == '-') {
                return LocalDateTime.parse(normalized.substring(0, 16), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private LocalDateTime parseSinaFutureQuoteTime(String[] fields) {
        LocalDate date = fields.length > 17 ? parseLocalDate(fields[17]) : null;
        LocalTime time = fields.length > 1 ? parseSinaCompactTime(fields[1]) : null;
        if (date != null && time != null) {
            return date.atTime(time);
        }
        return parseSinaQuoteTime(fields);
    }

    private LocalDateTime parseSinaSpotGoldQuoteTime(String[] fields) {
        LocalDate date = fields.length > 12 ? parseLocalDate(fields[12]) : null;
        LocalTime time = fields.length > 6 ? parseSinaTime(fields[6]) : null;
        if (date != null && time != null) {
            return date.atTime(time);
        }
        return parseSinaQuoteTime(fields);
    }

    private LocalTime parseSinaCompactTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() != 6) {
            return parseSinaTime(normalized);
        }
        try {
            return LocalTime.parse(normalized, SINA_COMPACT_TIME_FORMATTER);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalTime parseSinaTime(String value) {
        if (value == null || value.isBlank() || "--".equals(value.trim())) {
            return null;
        }
        String normalized = value.trim();
        try {
            if (normalized.length() == 5) {
                return LocalTime.parse(normalized + ":00");
            }
            if (normalized.length() >= 8 && normalized.charAt(2) == ':') {
                return LocalTime.parse(normalized.substring(0, 8));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private LocalDateTime eastmoneyQuoteTime(String value) {
        if (value == null || value.isBlank() || "--".equals(value.trim())) {
            return LocalDateTime.now(CHINA_ZONE);
        }
        try {
            long epochSeconds = Long.parseLong(value.trim());
            if (epochSeconds <= 0) {
                return LocalDateTime.now(CHINA_ZONE);
            }
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), CHINA_ZONE);
        } catch (Exception ignored) {
            return LocalDateTime.now(CHINA_ZONE);
        }
    }

    private boolean isFundCode(String value) {
        if (value == null || value.length() != 6) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal ratioPct(BigDecimal price, BigDecimal base) {
        if (price == null || base == null || BigDecimal.ZERO.compareTo(base) == 0) {
            return null;
        }
        return price.subtract(base)
                .multiply(BigDecimal.valueOf(100))
                .divide(base, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal ratioCount(int count, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private String direction(BigDecimal value) {
        if (value == null || BigDecimal.ZERO.compareTo(value) == 0) {
            return "FLAT";
        }
        return value.compareTo(BigDecimal.ZERO) > 0 ? "UP" : "DOWN";
    }

    private BigDecimal tencentTurnover(String priceVolumeTurnover, String fallback) {
        if (priceVolumeTurnover != null) {
            String[] parts = priceVolumeTurnover.split("/");
            if (parts.length >= 3) {
                BigDecimal turnover = decimal(parts[2]);
                if (turnover != null) {
                    return turnover;
                }
            }
        }
        return decimal(fallback);
    }

    private LocalDateTime parseTencentTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now(CHINA_ZONE);
        }
        try {
            return LocalDateTime.parse(value, TENCENT_TIME_FORMATTER);
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

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now(CHINA_ZONE);
        }
        String normalized = value.trim();
        try {
            if (normalized.length() == 16) {
                return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
            if (normalized.length() >= 19) {
                return LocalDateTime.parse(normalized.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            if (normalized.length() == 12) {
                return LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            }
        } catch (Exception ignored) {
        }
        return LocalDateTime.now(CHINA_ZONE);
    }

    private LocalDate localDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private static BigDecimal sum(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.add(right);
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    private static BigDecimal min(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.compareTo(right) <= 0 ? left : right;
    }

    private DateRange dateRange(String range) {
        LocalDate endDate = LocalDate.now(CHINA_ZONE);
        String normalized = range == null || range.isBlank() ? "1m" : range.trim().toLowerCase();
        LocalDate startDate = switch (normalized) {
            case "3m" -> endDate.minusMonths(3);
            case "6m" -> endDate.minusMonths(6);
            case "1y" -> endDate.minusYears(1);
            default -> {
                normalized = "1m";
                yield endDate.minusMonths(1);
            }
        };
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

    private boolean isTradingSessionDay(LocalDateTime now) {
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
                && !now.toLocalTime().isBefore(LocalTime.of(9, 30));
    }

    private boolean isChinaMarket(DefaultIndex index) {
        return index != null && ("SSE".equals(index.market()) || "SZSE".equals(index.market()));
    }

    private String currencyForMarket(String market) {
        return switch (market) {
            case "HKEX" -> "HKD";
            case "NASDAQ", "NYSE", "AMEX", "OTHER", "FX" -> "USD";
            default -> "CNY";
        };
    }

    private String currencyForMarket(String market, String symbol) {
        if ("AU9999".equalsIgnoreCase(symbol == null ? "" : symbol)) {
            return "CNY";
        }
        return currencyForMarket(market);
    }

    private boolean isPreOpenClearWindow(LocalDateTime now, DefaultIndex index) {
        if (index == null) {
            return false;
        }
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        if (isChinaMarket(index)) {
            if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                return false;
            }
            return !time.isBefore(LocalTime.of(8, 0)) && time.isBefore(LocalTime.of(9, 30));
        }
        if (isUsMarket(index)) {
            LocalDateTime usNow = usLocalTime(now);
            DayOfWeek usDayOfWeek = usNow.getDayOfWeek();
            if (usDayOfWeek == DayOfWeek.SATURDAY || usDayOfWeek == DayOfWeek.SUNDAY) {
                return false;
            }
            LocalTime usTime = usNow.toLocalTime();
            return !usTime.isBefore(LocalTime.of(8, 0)) && usTime.isBefore(LocalTime.of(9, 30));
        }
        if (isShfeMarket(index)) {
            if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
                return false;
            }
            return !time.isBefore(LocalTime.of(20, 0)) && time.isBefore(LocalTime.of(20, 45));
        }
        return false;
    }

    private boolean isCurrentTradingData(List<MarketDtos.IndexQuoteResponse> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return false;
        }
        LocalDate expectedDay = expectedTradingDay(LocalDateTime.now(CHINA_ZONE));
        return quotes.stream()
                .map(MarketDtos.IndexQuoteResponse::quoteTime)
                .filter(time -> time != null)
                .map(LocalDateTime::toLocalDate)
                .anyMatch(expectedDay::equals);
    }

    private boolean isSameOrNewer(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right == null;
        }
        return right == null || !left.isBefore(right);
    }

    private boolean shouldReplaceWithHistoryFallback(DefaultIndex index,
                                                     List<MarketDtos.HistoryPointResponse> points,
                                                     LocalDate endDate) {
        if (index == null || isChinaMarket(index) || points == null || points.isEmpty()) {
            return false;
        }
        if (points.size() < 5) {
            return true;
        }
        LocalDate latest = points.stream()
                .map(MarketDtos.HistoryPointResponse::tradingDay)
                .filter(day -> day != null)
                .max(LocalDate::compareTo)
                .orElse(null);
        return latest == null || latest.isBefore(endDate.minusDays(10));
    }

    private String historyDataStatus(DefaultIndex index,
                                     List<MarketDtos.HistoryPointResponse> points,
                                     DateRange dateRange) {
        if (points == null || points.isEmpty()) {
            return "NO_DATA";
        }
        return shouldReplaceWithHistoryFallback(index, points, dateRange.endDate()) ? "STALE" : "NORMAL";
    }

    private int dataLagSeconds(LocalDateTime quoteTime) {
        if (quoteTime == null) {
            return 0;
        }
        return Math.max(0, (int) Duration.between(quoteTime, LocalDateTime.now(CHINA_ZONE)).getSeconds());
    }

    private boolean isFutureQuoteTime(LocalDateTime quoteTime) {
        return quoteTime != null && quoteTime.isAfter(LocalDateTime.now(CHINA_ZONE).plusMinutes(2));
    }

    private LocalDate expectedTradingDay(LocalDateTime now) {
        return expectedTradingDay(now, null);
    }

    private LocalDate expectedTradingDay(LocalDateTime now, DefaultIndex index) {
        LocalDate day = now.toLocalDate();
        LocalTime time = now.toLocalTime();

        if (isUsMarket(index)) {
            LocalDateTime usNow = usLocalTime(now);
            LocalDate usDay = usNow.toLocalDate();
            if (isWeekend(usDay)) {
                return previousWeekday(usDay.minusDays(1));
            }
            return usNow.toLocalTime().isBefore(LocalTime.of(9, 30))
                    ? previousWeekday(usDay.minusDays(1))
                    : usDay;
        }

        if (isShfeMarket(index) || isSgeMarket(index)) {
            if (isWeekend(day)) {
                return previousWeekday(day.minusDays(1));
            }
            if (!time.isBefore(LocalTime.of(20, 0))) {
                return nextWeekday(day.plusDays(1));
            }
            return day;
        }

        if (isOvernightSpotMarket(index)) {
            if (isWeekend(day)) {
                return previousWeekday(day.minusDays(1));
            }
            return time.isBefore(LocalTime.of(6, 0))
                    ? previousWeekday(day.minusDays(1))
                    : day;
        }

        if (isWeekend(day) || time.isBefore(LocalTime.of(9, 30))) {
            return previousWeekday(day.minusDays(1));
        }
        return day;
    }

    private LocalDate previousWeekday(LocalDate day) {
        LocalDate cursor = day;
        while (isWeekend(cursor)) {
            cursor = cursor.minusDays(1);
        }
        return cursor;
    }

    private LocalDate nextWeekday(LocalDate day) {
        LocalDate cursor = day;
        while (isWeekend(cursor)) {
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }

    private boolean isWeekend(LocalDate day) {
        DayOfWeek dayOfWeek = day.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }

    private List<MarketDtos.IndexQuoteResponse> sortDefaultIndices(List<MarketDtos.IndexQuoteResponse> quotes) {
        return quotes.stream()
                .sorted((left, right) -> Integer.compare(defaultOrder(left.symbol()), defaultOrder(right.symbol())))
                .toList();
    }

    private int defaultOrder(String symbol) {
        for (int i = 0; i < DEFAULT_INDICES.size(); i++) {
            if (DEFAULT_INDICES.get(i).symbol().equalsIgnoreCase(symbol == null ? "" : symbol)) {
                return i;
            }
        }
        return 99;
    }

    private LocalDate latestTradingDay() {
        List<LocalDate> days = jdbcTemplate.query("""
                SELECT MAX(trading_day) AS trading_day FROM market_quote_latest
                """, Map.of(), (rs, rowNum) -> {
            Date date = rs.getDate("trading_day");
            return date == null ? null : date.toLocalDate();
        });
        return days.isEmpty() ? null : days.get(0);
    }

    private LocalDateTime latestQuoteTime() {
        List<LocalDateTime> times = jdbcTemplate.query("""
                SELECT MAX(quote_time) AS quote_time FROM market_quote_latest
                """, Map.of(), (rs, rowNum) -> timestamp(rs.getTimestamp("quote_time")));
        return times.isEmpty() || times.get(0) == null ? LocalDateTime.now(CHINA_ZONE) : times.get(0);
    }

    private LocalDate latestMinuteTradingDay(long instrumentId) {
        List<LocalDate> days = jdbcTemplate.query("""
                SELECT MAX(trading_day) AS trading_day
                FROM market_quote_minute
                WHERE instrument_id = :instrumentId
                """, Map.of("instrumentId", instrumentId), (rs, rowNum) -> {
            Date date = rs.getDate("trading_day");
            return date == null ? null : date.toLocalDate();
        });
        return days.isEmpty() ? null : days.get(0);
    }

    private LocalDate latestMinuteTradingDayOnOrBefore(long instrumentId, LocalDate maxDay) {
        if (maxDay == null) {
            return latestMinuteTradingDay(instrumentId);
        }
        List<LocalDate> days = jdbcTemplate.query("""
                SELECT MAX(trading_day) AS trading_day
                FROM market_quote_minute
                WHERE instrument_id = :instrumentId
                  AND trading_day <= :maxDay
                """, Map.of("instrumentId", instrumentId, "maxDay", maxDay), (rs, rowNum) -> {
            Date date = rs.getDate("trading_day");
            return date == null ? null : date.toLocalDate();
        });
        return days.isEmpty() ? null : days.get(0);
    }

    private DefaultIndex defaultIndex(String symbol) {
        return DEFAULT_INDICES.stream()
                .filter(item -> item.symbol().equalsIgnoreCase(symbol == null ? "" : symbol))
                .findFirst()
                .orElse(null);
    }

    private DefaultIndex defaultIndexBySinaQuoteCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return DEFAULT_INDICES.stream()
                .filter(item -> code.equalsIgnoreCase(sinaQuoteCode(item)))
                .findFirst()
                .orElse(null);
    }

    private String sinaQuoteCode(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        return switch (index.symbol()) {
            case "HSI" -> "rt_hkHSI";
            case "HSCEI" -> "rt_hkHSCEI";
            case "HSTECH" -> "rt_hkHSTECH";
            case "DJIA" -> "gb_dji";
            case "SPX" -> "gb_inx";
            case "NDX100" -> "gb_ndx";
            case "GC" -> "hf_GC";
            default -> null;
        };
    }

    private String sinaMinuteSymbol(DefaultIndex index) {
        if (index == null || !"HKEX".equals(index.market())) {
            return null;
        }
        return switch (index.symbol()) {
            case "HSI" -> "HSI";
            case "HSCEI" -> "HSCEI";
            case "HSTECH" -> "HSTECH";
            default -> null;
        };
    }

    private String sinaDailyKlineSymbol(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        return switch (index.symbol()) {
            case "DJIA" -> ".DJI";
            case "SPX" -> ".INX";
            case "NDX100" -> ".NDX";
            default -> null;
        };
    }

    private String sinaFutureSymbol(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        return switch (index.symbol()) {
            case "AUM" -> "AU0";
            default -> null;
        };
    }

    private String sinaGlobalFutureSymbol(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        return switch (index.symbol()) {
            case "GC" -> "GC";
            default -> null;
        };
    }

    private String eastmoneyFutureStaticCode(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        return switch (index.symbol()) {
            default -> null;
        };
    }

    private String yahooChartSymbol(DefaultIndex index) {
        if (index == null) {
            return null;
        }
        return switch (index.symbol()) {
            case "HSI" -> "^HSI";
            case "HSCEI" -> "^HSCE";
            case "HSTECH" -> "^HSTECH";
            case "DJIA" -> "^DJI";
            case "SPX" -> "^GSPC";
            case "NDX100" -> "^NDX";
            case "GC" -> "GC=F";
            default -> null;
        };
    }

    private String yahooChartUrl(String symbol, String range, String interval) {
        return YAHOO_CHART_URL + urlEncode(symbol)
                + "?range=" + range
                + "&interval=" + interval
                + "&includePrePost=false";
    }

    private String yahooDailyChartUrl(String symbol, LocalDate startDate, LocalDate endDate) {
        long period1 = startDate.atStartOfDay(CHINA_ZONE).toEpochSecond();
        long period2 = endDate.plusDays(1).atStartOfDay(CHINA_ZONE).toEpochSecond();
        return YAHOO_CHART_URL + urlEncode(symbol)
                + "?period1=" + period1
                + "&period2=" + period2
                + "&interval=1d&includePrePost=false";
    }

    private JsonNode yahooChartResult(String url) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode result = objectMapper.readTree(fetchUtf8Text(url))
                .path("chart")
                .path("result");
        return result.isArray() && !result.isEmpty() ? result.get(0) : null;
    }

    private LocalDateTime usLocalTime(LocalDateTime chinaTime) {
        if (chinaTime == null) {
            return null;
        }
        return chinaTime.atZone(CHINA_ZONE).withZoneSameInstant(US_EASTERN_ZONE).toLocalDateTime();
    }

    private LocalDateTime marketDisplayTime(DefaultIndex index, LocalDateTime chinaTime) {
        return isUsMarket(index) ? usLocalTime(chinaTime) : chinaTime;
    }

    private LocalDateTime usSessionStartInChina(LocalDate tradingDay) {
        return usSessionTimeInChina(tradingDay, LocalTime.of(9, 30));
    }

    private LocalDateTime usSessionEndInChina(LocalDate tradingDay) {
        return usSessionTimeInChina(tradingDay, LocalTime.of(16, 0));
    }

    private LocalDateTime usSessionTimeInChina(LocalDate tradingDay, LocalTime time) {
        return tradingDay.atTime(time)
                .atZone(US_EASTERN_ZONE)
                .withZoneSameInstant(CHINA_ZONE)
                .toLocalDateTime();
    }

    private LocalDate marketTradingDay(DefaultIndex index, LocalDateTime quoteTime) {
        if (quoteTime == null) {
            return expectedTradingDay(LocalDateTime.now(CHINA_ZONE), index);
        }
        if (isShfeMarket(index) || isSgeMarket(index)) {
            LocalTime time = quoteTime.toLocalTime();
            if (!time.isBefore(LocalTime.of(20, 0))) {
                return nextWeekday(quoteTime.toLocalDate().plusDays(1));
            }
        }
        if (isUsMarket(index)) {
            return quoteTime.atZone(CHINA_ZONE).withZoneSameInstant(US_EASTERN_ZONE).toLocalDate();
        }
        if (isOvernightSpotMarket(index) && quoteTime.toLocalTime().isBefore(LocalTime.of(6, 0))) {
            return previousWeekday(quoteTime.toLocalDate().minusDays(1));
        }
        return quoteTime.toLocalDate();
    }

    private boolean isShfeMarket(DefaultIndex index) {
        return index != null && "SHFE".equals(index.market());
    }

    private boolean isHkMarket(DefaultIndex index) {
        return index != null && "HKEX".equals(index.market());
    }

    private boolean isSgeMarket(DefaultIndex index) {
        return index != null && "AU9999".equals(index.symbol());
    }

    private boolean isOfficialGoldMarket(DefaultIndex index) {
        return index != null && "AU9999".equals(index.symbol());
    }

    private boolean isGlobalGoldMarket(DefaultIndex index) {
        return index != null && "GC".equals(index.symbol());
    }

    private boolean isUsMarket(DefaultIndex index) {
        return index != null && ("NYSE".equals(index.market()) || "NASDAQ".equals(index.market()) || "AMEX".equals(index.market()));
    }

    private boolean isOvernightSpotMarket(DefaultIndex index) {
        return index != null && !isSgeMarket(index) && ("OTHER".equals(index.market()) || "FX".equals(index.market()));
    }

    private JsonNode firstDataArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return node;
            }
            JsonNode first = node.get(0);
            if (first != null && (first.isObject() || first.isArray())) {
                return node;
            }
            return null;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = firstDataArray(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private LocalDate sinaRowDate(JsonNode row) {
        LocalDateTime dateTime = sinaRowDateTime(row, null);
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    private LocalDateTime sinaRowDateTime(JsonNode row, LocalDate fallbackDate) {
        String value = rowText(row, "d", "date", "day", "time", "datetime", 0);
        LocalDateTime dateTime = parseSinaDateTime(value);
        if (dateTime != null) {
            return dateTime;
        }
        LocalDate date = parseLocalDate(value);
        if (date != null) {
            return date.atStartOfDay();
        }
        LocalTime time = parseSinaTime(value);
        if (time != null && fallbackDate != null) {
            return fallbackDate.atTime(time);
        }
        return null;
    }

    private String rowText(JsonNode row, String field1, String field2, String field3, String field4, String field5, int arrayIndex) {
        if (row == null || row.isNull()) {
            return null;
        }
        if (row.isObject()) {
            for (String field : List.of(field1, field2, field3, field4, field5)) {
                if (field != null && row.has(field) && !row.path(field).isNull()) {
                    return row.path(field).asText(null);
                }
            }
        }
        if (row.isArray() && row.size() > arrayIndex && !row.get(arrayIndex).isNull()) {
            return row.get(arrayIndex).asText(null);
        }
        return null;
    }

    private BigDecimal rowDecimal(JsonNode row, String field1, String field2, int arrayIndex) {
        return rowDecimal(row, field1, field2, null, null, arrayIndex);
    }

    private BigDecimal rowDecimal(JsonNode row, String field1, String field2, String field3, String field4, int... arrayIndexes) {
        if (row == null || row.isNull()) {
            return null;
        }
        if (row.isObject()) {
            for (String field : List.of(field1, field2, field3, field4)) {
                if (field != null && row.has(field)) {
                    BigDecimal value = decimal(row.path(field).asText(null));
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        if (row.isArray()) {
            for (int index : arrayIndexes) {
                if (index >= 0 && row.size() > index && !row.get(index).isNull()) {
                    BigDecimal value = decimal(row.get(index).asText(null));
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private JsonNode findArrayWithFields(JsonNode node, String firstField, String secondField) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return node;
            }
            JsonNode first = node.get(0);
            if (first != null && first.has(firstField) && first.has(secondField)) {
                return node;
            }
            for (JsonNode child : node) {
                JsonNode found = findArrayWithFields(child, firstField, secondField);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                JsonNode found = findArrayWithFields(child, firstField, secondField);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String extractJsonArray(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        int start = body.indexOf('[');
        int end = body.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return "";
        }
        return body.substring(start, end + 1);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static LocalDateTime timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record DefaultIndex(String group, String market, String symbol, String name, String tencentCode, String eastmoneySecid) {
    }

    private record SinaQuote(
            DefaultIndex index,
            String name,
            BigDecimal lastPrice,
            BigDecimal prevClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal changeAmount,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover,
            LocalDateTime quoteTime
    ) {
    }

    private record EastmoneyFutureQuote(
            DefaultIndex index,
            String name,
            BigDecimal lastPrice,
            BigDecimal prevClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal changeAmount,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover,
            LocalDateTime quoteTime
    ) {
    }

    private record OfficialGoldQuote(
            DefaultIndex index,
            String name,
            BigDecimal lastPrice,
            BigDecimal prevClose,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal changeAmount,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover,
            LocalDate tradingDay,
            LocalDateTime quoteTime,
            int dataLagSeconds
    ) {
    }

    private record FutureTick(
            LocalDateTime quoteTime,
            BigDecimal price,
            BigDecimal volume
    ) {
    }

    private record FundRankSnapshot(
            MarketDtos.FundBreadthResponse breadth,
            List<MarketDtos.FundRankingResponse> rankings
    ) {
    }

    private record CachedFundRankSnapshot(
            Instant fetchedAt,
            FundRankSnapshot snapshot
    ) {
    }

    private record InstrumentRef(Long id, String market, String symbol, String name) {
    }

    private record EastmoneyTrendPoint(
            LocalDate tradingDay,
            LocalDateTime quoteTime,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover
    ) {
    }

    private record StoredMinutePoint(
            LocalDate tradingDay,
            LocalDateTime quoteTime,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal changePct,
            BigDecimal volume,
            BigDecimal turnover
    ) {
    }

    private static final class FutureMinuteBar {
        private final LocalDateTime minute;
        private BigDecimal openPrice;
        private BigDecimal closePrice;
        private BigDecimal highPrice;
        private BigDecimal lowPrice;
        private BigDecimal volume;

        private FutureMinuteBar(LocalDateTime minute) {
            this.minute = minute;
        }

        private void add(FutureTick tick) {
            if (tick == null || tick.price() == null) {
                return;
            }
            if (openPrice == null) {
                openPrice = tick.price();
                highPrice = tick.price();
                lowPrice = tick.price();
                volume = BigDecimal.ZERO;
            }
            closePrice = tick.price();
            if (highPrice == null || tick.price().compareTo(highPrice) > 0) {
                highPrice = tick.price();
            }
            if (lowPrice == null || tick.price().compareTo(lowPrice) < 0) {
                lowPrice = tick.price();
            }
            if (tick.volume() != null) {
                volume = volume == null ? tick.volume() : volume.add(tick.volume());
            }
        }

        private LocalDateTime minute() {
            return minute;
        }

        private BigDecimal openPrice() {
            return openPrice;
        }

        private BigDecimal closePrice() {
            return closePrice;
        }

        private BigDecimal highPrice() {
            return highPrice;
        }

        private BigDecimal lowPrice() {
            return lowPrice;
        }

        private BigDecimal volume() {
            return volume;
        }
    }

    private static final class StoredDailyBar {
        private final LocalDate tradingDay;
        private BigDecimal openPrice;
        private BigDecimal closePrice;
        private BigDecimal highPrice;
        private BigDecimal lowPrice;
        private BigDecimal changePct;
        private BigDecimal volume;
        private BigDecimal turnover;

        private StoredDailyBar(LocalDate tradingDay) {
            this.tradingDay = tradingDay;
        }

        private void add(StoredMinutePoint point) {
            BigDecimal price = point.closePrice() == null ? point.openPrice() : point.closePrice();
            if (openPrice == null) {
                openPrice = point.openPrice() == null ? price : point.openPrice();
            }
            closePrice = price;
            highPrice = max(highPrice, point.highPrice() == null ? price : point.highPrice());
            lowPrice = min(lowPrice, point.lowPrice() == null ? price : point.lowPrice());
            if (point.changePct() != null) {
                changePct = point.changePct();
            }
            volume = sum(volume, point.volume());
            turnover = sum(turnover, point.turnover());
        }

        private MarketDtos.HistoryPointResponse toResponse() {
            return new MarketDtos.HistoryPointResponse(
                    tradingDay,
                    openPrice,
                    closePrice,
                    highPrice,
                    lowPrice,
                    changePct,
                    volume,
                    turnover
            );
        }
    }

    private record DateRange(String range, LocalDate startDate, LocalDate endDate) {
    }
}

