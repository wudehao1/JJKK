package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.common.BusinessException;
import com.wdh.jjkk_2.dto.FundDtos;
import com.wdh.jjkk_2.dto.ProfitAnalysisDtos;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

@Service
public class ProfitAnalysisService {
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final Set<String> TREND_RANGES = Set.of("week", "month", "year", "all");
    private static final Set<String> CALENDAR_MODES = Set.of("day", "month", "year");

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final IntradayRedisCacheService intradayRedisCacheService;

    public ProfitAnalysisService(
            NamedParameterJdbcTemplate jdbcTemplate,
            IntradayRedisCacheService intradayRedisCacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.intradayRedisCacheService = intradayRedisCacheService;
    }

    public ProfitAnalysisDtos.AnalysisResponse analyze(
            Long userId,
            Long accountId,
            String range,
            String calendarMode,
            String anchor,
            String detailKey
    ) {
        assertUserExists(userId);
        if (accountId != null) {
            assertAccountBelongsUser(userId, accountId);
        }

        LocalDate today = LocalDate.now(CHINA_ZONE);
        String safeRange = normalize(range, TREND_RANGES, "week");
        String safeCalendarMode = normalize(calendarMode, CALENDAR_MODES, "day");
        LocalDate safeAnchor = parseDate(anchor, today);
        List<ProfitAnalysisDtos.AccountResponse> accounts = loadAccounts(userId);
        List<PositionRow> positions = loadPositions(userId, accountId);
        List<TradeRow> trades = loadTrades(userId, accountId);

        Map<PositionKey, List<ShareEvent>> eventsByPosition = buildShareEvents(positions, trades);
        LocalDate activityStart = findActivityStart(positions, eventsByPosition, today);
        Set<String> fundCodes = collectFundCodes(positions, trades);
        Map<String, String> fundNames = collectFundNames(positions, trades);
        Map<String, NavigableMap<LocalDate, BigDecimal>> navs = loadNavs(fundCodes, activityStart.minusDays(40), today);

        NavigableMap<LocalDate, DailyValue> dailyValues = buildOfficialDailyValues(
                eventsByPosition,
                fundNames,
                navs,
                activityStart,
                today
        );

        Map<String, BigDecimal> openingShares = openingSharesByFund(eventsByPosition, today);
        Map<String, SnapshotRow> snapshots = loadSnapshots(fundCodes);
        CurrentResult current = buildCurrentResult(
                today,
                positions,
                openingShares,
                fundNames,
                navs,
                snapshots
        );
        if (current.hasData()) {
            dailyValues.put(today, current.dailyValue());
        }

        List<ProfitAnalysisDtos.PointResponse> intraday = buildIntraday(
                today,
                openingShares,
                navs,
                snapshots
        );
        List<ProfitAnalysisDtos.PointResponse> trend = buildTrend(dailyValues, safeRange, activityStart, today);
        List<ProfitAnalysisDtos.CalendarItemResponse> calendar = buildCalendar(
                dailyValues,
                safeCalendarMode,
                safeAnchor,
                activityStart,
                today
        );
        String selectedKey = resolveSelectedKey(safeCalendarMode, detailKey, today);
        String accountLabel = resolveAccountLabel(accounts, accountId);
        List<ProfitAnalysisDtos.DetailResponse> details = buildDetails(
                dailyValues,
                safeCalendarMode,
                selectedKey,
                accountLabel
        );

        return new ProfitAnalysisDtos.AnalysisResponse(
                accountId,
                accounts,
                current.summary(),
                safeRange,
                intraday,
                trend,
                safeCalendarMode,
                safeAnchor,
                calendar,
                selectedKey,
                details,
                LocalDateTime.now(CHINA_ZONE)
        );
    }

    private List<ProfitAnalysisDtos.AccountResponse> loadAccounts(Long userId) {
        return jdbcTemplate.query("""
                SELECT acc.id,
                       acc.account_name,
                       acc.platform_name,
                       SUM(CASE WHEN h.id IS NOT NULL AND h.status <> 'DELETED' THEN 1 ELSE 0 END) AS holding_count
                FROM user_holding_account acc
                LEFT JOIN user_fund_holding h ON h.account_id = acc.id
                WHERE acc.user_id = :userId
                GROUP BY acc.id, acc.account_name, acc.platform_name
                ORDER BY acc.id
                """, Map.of("userId", userId), (rs, rowNum) -> new ProfitAnalysisDtos.AccountResponse(
                rs.getLong("id"),
                rs.getString("account_name"),
                rs.getString("platform_name"),
                rs.getInt("holding_count")
        ));
    }

    private List<PositionRow> loadPositions(Long userId, Long accountId) {
        String accountFilter = accountId == null ? "" : " AND h.account_id = :accountId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", accountId);
        return jdbcTemplate.query("""
                SELECT h.account_id,
                       acc.account_name,
                       h.fund_code,
                       fsc.fund_name,
                       h.holding_share,
                       h.first_buy_date,
                       h.created_at
                FROM user_fund_holding h
                JOIN user_holding_account acc ON acc.id = h.account_id
                JOIN fund_share_class fsc ON fsc.fund_code = h.fund_code
                WHERE h.user_id = :userId AND h.status <> 'DELETED'
                %s
                ORDER BY h.account_id, h.fund_code
                """.formatted(accountFilter), params, (rs, rowNum) -> new PositionRow(
                rs.getLong("account_id"),
                rs.getString("account_name"),
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                zero(rs.getBigDecimal("holding_share")),
                localDate(rs.getDate("first_buy_date")),
                localDate(rs.getTimestamp("created_at"))
        ));
    }

    private List<TradeRow> loadTrades(Long userId, Long accountId) {
        String accountFilter = accountId == null ? "" : " AND h.account_id = :accountId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", accountId);
        return jdbcTemplate.query("""
                SELECT h.account_id,
                       acc.account_name,
                       t.fund_code,
                       fsc.fund_name,
                       t.txn_type,
                       COALESCE(t.confirm_date, t.txn_date) AS effective_date,
                       t.share,
                       t.amount,
                       t.fee
                FROM user_holding_txn t
                JOIN user_fund_holding h ON h.id = t.holding_id
                JOIN user_holding_account acc ON acc.id = h.account_id
                JOIN fund_share_class fsc ON fsc.fund_code = t.fund_code
                WHERE t.user_id = :userId AND h.status <> 'DELETED'
                %s
                ORDER BY effective_date, t.id
                """.formatted(accountFilter), params, (rs, rowNum) -> new TradeRow(
                rs.getLong("account_id"),
                rs.getString("account_name"),
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("txn_type"),
                localDate(rs.getDate("effective_date")),
                zero(rs.getBigDecimal("share")),
                zero(rs.getBigDecimal("amount")),
                zero(rs.getBigDecimal("fee"))
        ));
    }

    private Map<PositionKey, List<ShareEvent>> buildShareEvents(List<PositionRow> positions, List<TradeRow> trades) {
        Map<PositionKey, List<ShareEvent>> result = new LinkedHashMap<>();
        for (TradeRow trade : trades) {
            BigDecimal delta = switch (trade.txnType()) {
                case "BUY", "DIVIDEND_REINVEST" -> trade.share();
                case "SELL" -> trade.share().negate();
                case "ADJUST" -> trade.share();
                default -> BigDecimal.ZERO;
            };
            if (trade.effectiveDate() != null && delta.compareTo(BigDecimal.ZERO) != 0) {
                result.computeIfAbsent(new PositionKey(trade.accountId(), trade.fundCode()), ignored -> new ArrayList<>())
                        .add(new ShareEvent(trade.effectiveDate(), delta));
            }
        }
        for (PositionRow position : positions) {
            PositionKey key = new PositionKey(position.accountId(), position.fundCode());
            if (!result.containsKey(key) && position.currentShare().compareTo(BigDecimal.ZERO) > 0) {
                LocalDate start = position.firstBuyDate() == null ? position.createdDate() : position.firstBuyDate();
                result.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new ShareEvent(start == null ? LocalDate.now(CHINA_ZONE) : start, position.currentShare()));
            }
        }
        result.values().forEach(items -> items.sort(Comparator.comparing(ShareEvent::date)));
        return result;
    }

    private NavigableMap<LocalDate, DailyValue> buildOfficialDailyValues(
            Map<PositionKey, List<ShareEvent>> eventsByPosition,
            Map<String, String> fundNames,
            Map<String, NavigableMap<LocalDate, BigDecimal>> navs,
            LocalDate activityStart,
            LocalDate today
    ) {
        NavigableMap<LocalDate, DailyValue> result = new TreeMap<>();
        for (Map.Entry<PositionKey, List<ShareEvent>> position : eventsByPosition.entrySet()) {
            String fundCode = position.getKey().fundCode();
            NavigableMap<LocalDate, BigDecimal> fundNavs = navs.get(fundCode);
            if (fundNavs == null || fundNavs.isEmpty()) {
                continue;
            }
            BigDecimal share = BigDecimal.ZERO;
            BigDecimal previousNav = null;
            int eventIndex = 0;
            List<ShareEvent> events = position.getValue();
            for (Map.Entry<LocalDate, BigDecimal> navEntry : fundNavs.entrySet()) {
                LocalDate date = navEntry.getKey();
                if (date.isAfter(today)) {
                    break;
                }
                while (eventIndex < events.size() && events.get(eventIndex).date().isBefore(date)) {
                    share = share.add(events.get(eventIndex).delta());
                    eventIndex += 1;
                }
                if (previousNav != null && !date.isBefore(activityStart) && share.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal base = share.multiply(previousNav);
                    BigDecimal amount = share.multiply(navEntry.getValue().subtract(previousNav));
                    result.computeIfAbsent(date, ignored -> new DailyValue())
                            .add(fundCode, fundNames.getOrDefault(fundCode, fundCode), amount, base, share);
                }
                previousNav = navEntry.getValue();
            }
        }
        return result;
    }

    private CurrentResult buildCurrentResult(
            LocalDate today,
            List<PositionRow> positions,
            Map<String, BigDecimal> openingShares,
            Map<String, String> fundNames,
            Map<String, NavigableMap<LocalDate, BigDecimal>> navs,
            Map<String, SnapshotRow> snapshots
    ) {
        DailyValue daily = new DailyValue();
        BigDecimal currentMarketValue = BigDecimal.ZERO;
        LocalDateTime updatedAt = null;
        int expectedFunds = 0;
        int availableFunds = 0;
        boolean realtime = false;

        Map<String, BigDecimal> currentShares = new HashMap<>();
        for (PositionRow position : positions) {
            currentShares.merge(position.fundCode(), position.currentShare(), BigDecimal::add);
        }

        for (Map.Entry<String, BigDecimal> opening : openingShares.entrySet()) {
            if (opening.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            expectedFunds += 1;
            String fundCode = opening.getKey();
            NavigableMap<LocalDate, BigDecimal> fundNavs = navs.get(fundCode);
            if (fundNavs == null || fundNavs.isEmpty()) {
                continue;
            }
            Map.Entry<LocalDate, BigDecimal> previousEntry = fundNavs.lowerEntry(today);
            Map.Entry<LocalDate, BigDecimal> todayEntry = fundNavs.floorEntry(today);
            SnapshotRow snapshot = snapshots.get(fundCode);
            BigDecimal currentNav = null;
            boolean hasTodayData = false;
            if (snapshot != null && today.equals(snapshot.tradingDay()) && snapshot.estimateNav() != null) {
                currentNav = snapshot.estimateNav();
                hasTodayData = true;
                realtime = true;
                if (snapshot.quoteTime() != null && (updatedAt == null || snapshot.quoteTime().isAfter(updatedAt))) {
                    updatedAt = snapshot.quoteTime();
                }
            } else if (todayEntry != null && today.equals(todayEntry.getKey())) {
                currentNav = todayEntry.getValue();
                hasTodayData = true;
                LocalDateTime officialTime = today.atTime(15, 0);
                if (updatedAt == null || officialTime.isAfter(updatedAt)) {
                    updatedAt = officialTime;
                }
            }
            if (previousEntry == null || currentNav == null || !hasTodayData) {
                continue;
            }
            availableFunds += 1;
            BigDecimal base = opening.getValue().multiply(previousEntry.getValue());
            BigDecimal amount = opening.getValue().multiply(currentNav.subtract(previousEntry.getValue()));
            daily.add(fundCode, fundNames.getOrDefault(fundCode, fundCode), amount, base, opening.getValue());
        }

        for (Map.Entry<String, BigDecimal> current : currentShares.entrySet()) {
            BigDecimal currentNav = currentNav(today, navs.get(current.getKey()), snapshots.get(current.getKey()));
            if (currentNav != null) {
                currentMarketValue = currentMarketValue.add(current.getValue().multiply(currentNav));
            }
        }

        String status;
        if (availableFunds == 0) {
            status = "NO_DATA";
        } else if (availableFunds < expectedFunds) {
            status = "PARTIAL";
        } else {
            status = realtime ? "REALTIME" : "OFFICIAL";
        }
        ProfitAnalysisDtos.SummaryResponse summary = new ProfitAnalysisDtos.SummaryResponse(
                today,
                money(daily.amount),
                rate(daily.amount, daily.base),
                money(daily.base),
                money(currentMarketValue),
                updatedAt == null ? LocalDateTime.now(CHINA_ZONE) : updatedAt,
                status
        );
        return new CurrentResult(availableFunds > 0, daily, summary);
    }

    private List<ProfitAnalysisDtos.PointResponse> buildIntraday(
            LocalDate today,
            Map<String, BigDecimal> openingShares,
            Map<String, NavigableMap<LocalDate, BigDecimal>> navs,
            Map<String, SnapshotRow> snapshots
    ) {
        Map<String, BigDecimal> previousNavs = new HashMap<>();
        BigDecimal totalBase = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> opening : openingShares.entrySet()) {
            if (opening.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            NavigableMap<LocalDate, BigDecimal> fundNavs = navs.get(opening.getKey());
            Map.Entry<LocalDate, BigDecimal> previous = fundNavs == null ? null : fundNavs.lowerEntry(today);
            if (previous != null) {
                previousNavs.put(opening.getKey(), previous.getValue());
                totalBase = totalBase.add(opening.getValue().multiply(previous.getValue()));
            }
        }
        if (previousNavs.isEmpty()) {
            return List.of();
        }

        NavigableMap<LocalDateTime, List<MinuteValue>> timeline = new TreeMap<>();
        for (String fundCode : previousNavs.keySet()) {
            List<FundDtos.EstimateMinutePointResponse> points = intradayRedisCacheService.loadFundMinutePoints(fundCode, today);
            if (points.isEmpty()) {
                points = loadMinutePointsFromDatabase(fundCode, today);
            }
            if (points.isEmpty()) {
                SnapshotRow snapshot = snapshots.get(fundCode);
                if (snapshot != null && today.equals(snapshot.tradingDay()) && snapshot.quoteTime() != null) {
                    points = List.of(new FundDtos.EstimateMinutePointResponse(
                            snapshot.quoteTime(),
                            snapshot.estimateNav(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null
                    ));
                }
            }
            for (FundDtos.EstimateMinutePointResponse point : points) {
                if (point == null || point.quoteTime() == null || point.estimateNav() == null) {
                    continue;
                }
                LocalDateTime minute = point.quoteTime().truncatedTo(ChronoUnit.MINUTES);
                if (!today.equals(minute.toLocalDate()) || !isTradingMinute(minute.toLocalTime())) {
                    continue;
                }
                timeline.computeIfAbsent(minute, ignored -> new ArrayList<>())
                        .add(new MinuteValue(fundCode, point.estimateNav()));
            }
        }

        Map<String, BigDecimal> latestEstimate = new HashMap<>();
        List<ProfitAnalysisDtos.PointResponse> result = new ArrayList<>();
        for (Map.Entry<LocalDateTime, List<MinuteValue>> entry : timeline.entrySet()) {
            for (MinuteValue value : entry.getValue()) {
                latestEstimate.put(value.fundCode(), value.estimateNav());
            }
            BigDecimal amount = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> previous : previousNavs.entrySet()) {
                BigDecimal estimate = latestEstimate.get(previous.getKey());
                if (estimate != null) {
                    amount = amount.add(openingShares.get(previous.getKey()).multiply(estimate.subtract(previous.getValue())));
                }
            }
            result.add(new ProfitAnalysisDtos.PointResponse(
                    entry.getKey().toString(),
                    today,
                    entry.getKey(),
                    money(amount),
                    rate(amount, totalBase)
            ));
        }
        return result;
    }

    private List<FundDtos.EstimateMinutePointResponse> loadMinutePointsFromDatabase(String fundCode, LocalDate today) {
        return jdbcTemplate.query("""
                SELECT quote_time,
                       estimate_nav,
                       estimate_return_pct,
                       estimate_change_amount,
                       official_last_nav,
                       official_nav_date,
                       confidence_score,
                       data_lag_seconds
                FROM fund_estimate_minute
                WHERE fund_code = :fundCode AND trading_day = :today
                  AND TIME(quote_time) BETWEEN '09:30:00' AND '15:00:00'
                ORDER BY quote_time
                """, Map.of("fundCode", fundCode, "today", today), (rs, rowNum) ->
                new FundDtos.EstimateMinutePointResponse(
                        localDateTime(rs.getTimestamp("quote_time")),
                        rs.getBigDecimal("estimate_nav"),
                        rs.getBigDecimal("estimate_return_pct"),
                        rs.getBigDecimal("estimate_change_amount"),
                        rs.getBigDecimal("official_last_nav"),
                        localDate(rs.getDate("official_nav_date")),
                        rs.getBigDecimal("confidence_score"),
                        (Integer) rs.getObject("data_lag_seconds")
                ));
    }

    private List<ProfitAnalysisDtos.PointResponse> buildTrend(
            NavigableMap<LocalDate, DailyValue> dailyValues,
            String range,
            LocalDate activityStart,
            LocalDate today
    ) {
        LocalDate start = switch (range) {
            case "month" -> today.withDayOfMonth(1);
            case "year" -> today.withDayOfYear(1);
            case "all" -> activityStart;
            default -> today.with(java.time.DayOfWeek.MONDAY);
        };
        BigDecimal cumulativeAmount = BigDecimal.ZERO;
        double compoundFactor = 1D;
        List<ProfitAnalysisDtos.PointResponse> result = new ArrayList<>();
        for (Map.Entry<LocalDate, DailyValue> entry : dailyValues.entrySet()) {
            if (entry.getKey().isBefore(start) || entry.getKey().isAfter(today)) {
                continue;
            }
            DailyValue value = entry.getValue();
            cumulativeAmount = cumulativeAmount.add(value.amount);
            compoundFactor *= 1D + decimal(rate(value.amount, value.base)) / 100D;
            result.add(new ProfitAnalysisDtos.PointResponse(
                    entry.getKey().toString(),
                    entry.getKey(),
                    null,
                    money(cumulativeAmount),
                    pct(compoundFactor)
            ));
        }
        return result;
    }

    private List<ProfitAnalysisDtos.CalendarItemResponse> buildCalendar(
            NavigableMap<LocalDate, DailyValue> dailyValues,
            String mode,
            LocalDate anchor,
            LocalDate activityStart,
            LocalDate today
    ) {
        List<ProfitAnalysisDtos.CalendarItemResponse> result = new ArrayList<>();
        if ("day".equals(mode)) {
            LocalDate start = anchor.withDayOfMonth(1);
            LocalDate end = anchor.with(TemporalAdjusters.lastDayOfMonth());
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                DailyValue value = dailyValues.get(date);
                result.add(calendarItem(date.toString(), String.valueOf(date.getDayOfMonth()), date, date, value));
            }
            return result;
        }
        if ("month".equals(mode)) {
            for (int month = 1; month <= 12; month += 1) {
                YearMonth yearMonth = YearMonth.of(anchor.getYear(), month);
                LocalDate start = yearMonth.atDay(1);
                LocalDate end = yearMonth.atEndOfMonth();
                result.add(calendarItem(
                        yearMonth.toString(),
                        month + "\u6708",
                        start,
                        end,
                        dailyValues
                ));
            }
            return result;
        }
        int firstYear = Math.min(activityStart.getYear(), today.getYear());
        for (int year = firstYear; year <= today.getYear(); year += 1) {
            LocalDate start = LocalDate.of(year, 1, 1);
            LocalDate end = LocalDate.of(year, 12, 31);
            result.add(calendarItem(
                    String.valueOf(year),
                    year + "\u5e74",
                    start,
                    end,
                    dailyValues
            ));
        }
        return result;
    }

    private ProfitAnalysisDtos.CalendarItemResponse calendarItem(
            String key,
            String label,
            LocalDate start,
            LocalDate end,
            DailyValue value
    ) {
        boolean hasData = value != null && value.hasData;
        return new ProfitAnalysisDtos.CalendarItemResponse(
                key,
                label,
                start,
                end,
                hasData ? money(value.amount) : BigDecimal.ZERO.setScale(2),
                hasData ? rate(value.amount, value.base) : BigDecimal.ZERO.setScale(4),
                hasData
        );
    }

    private ProfitAnalysisDtos.CalendarItemResponse calendarItem(
            String key,
            String label,
            LocalDate start,
            LocalDate end,
            NavigableMap<LocalDate, DailyValue> dailyValues
    ) {
        BigDecimal amount = BigDecimal.ZERO;
        double compoundFactor = 1D;
        boolean hasData = false;
        for (DailyValue value : dailyValues.subMap(start, true, end, true).values()) {
            amount = amount.add(value.amount);
            compoundFactor *= 1D + decimal(rate(value.amount, value.base)) / 100D;
            hasData = true;
        }
        return new ProfitAnalysisDtos.CalendarItemResponse(
                key,
                label,
                start,
                end,
                hasData ? money(amount) : BigDecimal.ZERO.setScale(2),
                hasData ? pct(compoundFactor) : BigDecimal.ZERO.setScale(4),
                hasData
        );
    }

    private List<ProfitAnalysisDtos.DetailResponse> buildDetails(
            NavigableMap<LocalDate, DailyValue> dailyValues,
            String mode,
            String selectedKey,
            String accountLabel
    ) {
        DatePeriod period = parsePeriod(mode, selectedKey);
        if (period == null) {
            return List.of();
        }
        Map<String, DetailValue> details = new LinkedHashMap<>();
        for (DailyValue daily : dailyValues.subMap(period.start(), true, period.end(), true).values()) {
            for (DetailValue value : daily.details.values()) {
                details.computeIfAbsent(value.fundCode, ignored -> new DetailValue(value.fundCode, value.fundName))
                        .add(value.amount, value.base, value.share);
            }
        }
        BigDecimal totalAbsolute = details.values().stream()
                .map(value -> value.amount.abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return details.values().stream()
                .sorted((left, right) -> right.amount.abs().compareTo(left.amount.abs()))
                .map(value -> new ProfitAnalysisDtos.DetailResponse(
                        value.fundCode,
                        value.fundName,
                        accountLabel,
                        money(value.amount),
                        rate(value.amount, value.base),
                        rate(value.amount.abs(), totalAbsolute),
                        share(value.share)
                ))
                .toList();
    }

    private Map<String, NavigableMap<LocalDate, BigDecimal>> loadNavs(
            Set<String> fundCodes,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> result = new HashMap<>();
        if (fundCodes.isEmpty()) {
            return result;
        }
        List<NavRow> rows = jdbcTemplate.query("""
                SELECT fund_code, nav_date, unit_nav
                FROM fund_nav_daily
                WHERE fund_code IN (:fundCodes)
                  AND nav_date BETWEEN :fromDate AND :toDate
                  AND unit_nav IS NOT NULL
                ORDER BY fund_code, nav_date
                """, new MapSqlParameterSource()
                .addValue("fundCodes", fundCodes)
                .addValue("fromDate", fromDate)
                .addValue("toDate", toDate), (rs, rowNum) -> new NavRow(
                rs.getString("fund_code"),
                localDate(rs.getDate("nav_date")),
                rs.getBigDecimal("unit_nav")
        ));
        for (NavRow row : rows) {
            result.computeIfAbsent(row.fundCode(), ignored -> new TreeMap<>())
                    .put(row.date(), row.nav());
        }
        return result;
    }

    private Map<String, SnapshotRow> loadSnapshots(Set<String> fundCodes) {
        Map<String, SnapshotRow> result = new HashMap<>();
        if (fundCodes.isEmpty()) {
            return result;
        }
        List<SnapshotNamedRow> rows = jdbcTemplate.query("""
                SELECT fund_code, trading_day, quote_time, estimate_nav, data_status
                FROM fund_realtime_snapshot
                WHERE fund_code IN (:fundCodes)
                """, new MapSqlParameterSource("fundCodes", fundCodes), (rs, rowNum) -> new SnapshotNamedRow(
                rs.getString("fund_code"),
                new SnapshotRow(
                        localDate(rs.getDate("trading_day")),
                        localDateTime(rs.getTimestamp("quote_time")),
                        rs.getBigDecimal("estimate_nav"),
                        rs.getString("data_status")
                )
        ));
        for (SnapshotNamedRow row : rows) {
            result.put(row.fundCode(), row.snapshot());
        }
        return result;
    }

    private Map<String, BigDecimal> openingSharesByFund(
            Map<PositionKey, List<ShareEvent>> eventsByPosition,
            LocalDate date
    ) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map.Entry<PositionKey, List<ShareEvent>> position : eventsByPosition.entrySet()) {
            BigDecimal share = BigDecimal.ZERO;
            for (ShareEvent event : position.getValue()) {
                if (!event.date().isBefore(date)) {
                    break;
                }
                share = share.add(event.delta());
            }
            if (share.compareTo(BigDecimal.ZERO) > 0) {
                result.merge(position.getKey().fundCode(), share, BigDecimal::add);
            }
        }
        return result;
    }

    private LocalDate findActivityStart(
            List<PositionRow> positions,
            Map<PositionKey, List<ShareEvent>> eventsByPosition,
            LocalDate fallback
    ) {
        LocalDate result = null;
        for (List<ShareEvent> events : eventsByPosition.values()) {
            for (ShareEvent event : events) {
                if (result == null || event.date().isBefore(result)) {
                    result = event.date();
                }
            }
        }
        for (PositionRow position : positions) {
            LocalDate date = position.firstBuyDate() == null ? position.createdDate() : position.firstBuyDate();
            if (date != null && (result == null || date.isBefore(result))) {
                result = date;
            }
        }
        return result == null ? fallback : result;
    }

    private Set<String> collectFundCodes(List<PositionRow> positions, List<TradeRow> trades) {
        Set<String> result = new LinkedHashSet<>();
        positions.forEach(position -> result.add(position.fundCode()));
        trades.forEach(trade -> result.add(trade.fundCode()));
        return result;
    }

    private Map<String, String> collectFundNames(List<PositionRow> positions, List<TradeRow> trades) {
        Map<String, String> result = new HashMap<>();
        positions.forEach(position -> result.put(position.fundCode(), position.fundName()));
        trades.forEach(trade -> result.putIfAbsent(trade.fundCode(), trade.fundName()));
        return result;
    }

    private BigDecimal currentNav(
            LocalDate today,
            NavigableMap<LocalDate, BigDecimal> navs,
            SnapshotRow snapshot
    ) {
        if (snapshot != null && today.equals(snapshot.tradingDay()) && snapshot.estimateNav() != null) {
            return snapshot.estimateNav();
        }
        Map.Entry<LocalDate, BigDecimal> entry = navs == null ? null : navs.floorEntry(today);
        return entry == null ? null : entry.getValue();
    }

    private String resolveSelectedKey(String mode, String detailKey, LocalDate today) {
        if (StringUtils.hasText(detailKey) && parsePeriod(mode, detailKey.trim()) != null) {
            return detailKey.trim();
        }
        return switch (mode) {
            case "month" -> YearMonth.from(today).toString();
            case "year" -> String.valueOf(today.getYear());
            default -> today.toString();
        };
    }

    private DatePeriod parsePeriod(String mode, String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        try {
            if ("month".equals(mode)) {
                YearMonth month = YearMonth.parse(key);
                return new DatePeriod(month.atDay(1), month.atEndOfMonth());
            }
            if ("year".equals(mode)) {
                int year = Integer.parseInt(key);
                return new DatePeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            }
            LocalDate date = LocalDate.parse(key);
            return new DatePeriod(date, date);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveAccountLabel(List<ProfitAnalysisDtos.AccountResponse> accounts, Long accountId) {
        if (accountId == null) {
            return "\u5168\u90e8\u8d26\u6237";
        }
        return accounts.stream()
                .filter(account -> account.accountId().equals(accountId))
                .map(ProfitAnalysisDtos.AccountResponse::accountName)
                .findFirst()
                .orElse("\u672a\u77e5\u8d26\u6237");
    }

    private String normalize(String value, Set<String> allowed, String fallback) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase() : fallback;
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void assertUserExists(Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id = :userId AND status <> 'DELETED'",
                Map.of("userId", userId),
                Integer.class
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u7528\u6237\u4e0d\u5b58\u5728");
        }
    }

    private void assertAccountBelongsUser(Long userId, Long accountId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_holding_account WHERE id = :accountId AND user_id = :userId",
                Map.of("accountId", accountId, "userId", userId),
                Integer.class
        );
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6301\u6709\u8d26\u6237\u4e0d\u5b58\u5728");
        }
    }

    private boolean isTradingMinute(LocalTime time) {
        return !time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(15, 0));
    }

    private BigDecimal money(BigDecimal value) {
        return zero(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal share(BigDecimal value) {
        return zero(value).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return zero(numerator).multiply(ONE_HUNDRED).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(double compoundFactor) {
        return BigDecimal.valueOf((compoundFactor - 1D) * 100D).setScale(4, RoundingMode.HALF_UP);
    }

    private double decimal(BigDecimal value) {
        return value == null ? 0D : value.doubleValue();
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDate localDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private LocalDate localDate(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toLocalDate();
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private record PositionRow(
            Long accountId,
            String accountName,
            String fundCode,
            String fundName,
            BigDecimal currentShare,
            LocalDate firstBuyDate,
            LocalDate createdDate
    ) {
    }

    private record TradeRow(
            Long accountId,
            String accountName,
            String fundCode,
            String fundName,
            String txnType,
            LocalDate effectiveDate,
            BigDecimal share,
            BigDecimal amount,
            BigDecimal fee
    ) {
    }

    private record PositionKey(Long accountId, String fundCode) {
    }

    private record ShareEvent(LocalDate date, BigDecimal delta) {
    }

    private record SnapshotRow(
            LocalDate tradingDay,
            LocalDateTime quoteTime,
            BigDecimal estimateNav,
            String dataStatus
    ) {
    }

    private record MinuteValue(String fundCode, BigDecimal estimateNav) {
    }

    private record NavRow(String fundCode, LocalDate date, BigDecimal nav) {
    }

    private record SnapshotNamedRow(String fundCode, SnapshotRow snapshot) {
    }

    private record DatePeriod(LocalDate start, LocalDate end) {
    }

    private record CurrentResult(
            boolean hasData,
            DailyValue dailyValue,
            ProfitAnalysisDtos.SummaryResponse summary
    ) {
    }

    private static final class DailyValue {
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal base = BigDecimal.ZERO;
        private final Map<String, DetailValue> details = new LinkedHashMap<>();
        private boolean hasData;

        private void add(
                String fundCode,
                String fundName,
                BigDecimal profitAmount,
                BigDecimal baseAmount,
                BigDecimal holdingShare
        ) {
            amount = amount.add(profitAmount);
            base = base.add(baseAmount);
            details.computeIfAbsent(fundCode, ignored -> new DetailValue(fundCode, fundName))
                    .add(profitAmount, baseAmount, holdingShare);
            hasData = true;
        }

    }

    private static final class DetailValue {
        private final String fundCode;
        private final String fundName;
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal base = BigDecimal.ZERO;
        private BigDecimal share = BigDecimal.ZERO;

        private DetailValue(String fundCode, String fundName) {
            this.fundCode = fundCode;
            this.fundName = fundName;
        }

        private void add(BigDecimal profitAmount, BigDecimal baseAmount, BigDecimal holdingShare) {
            amount = amount.add(profitAmount);
            base = base.add(baseAmount);
            if (holdingShare != null && holdingShare.compareTo(share) > 0) {
                share = holdingShare;
            }
        }
    }
}
