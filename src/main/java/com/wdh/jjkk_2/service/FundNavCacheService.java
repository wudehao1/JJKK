package com.wdh.jjkk_2.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.dto.FundDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基金日净值读取链路：Redis -> MySQL -> 外部接口回填。
 *
 * 目标：
 * 1) 先走 Redis，降低 DB 压力；
 * 2) Redis miss 再走 MySQL；
 * 3) MySQL 仍无数据时，调用官方同步并回写到 MySQL 和 Redis。
 */
@Service
public class FundNavCacheService {
    private static final Logger log = LoggerFactory.getLogger(FundNavCacheService.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PREFIX = "jjkk:fund:nav:";
    private static final String NAV_SYNC_CURSOR_KEY = PREFIX + "sync:cursor:share_class_id";
    private static final Duration DAY_CACHE_TTL = Duration.ofHours(42);
    private static final Duration RANGE_CACHE_TTL = Duration.ofHours(6);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final FundQuoteRefreshService fundQuoteRefreshService;

    public FundNavCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NamedParameterJdbcTemplate jdbcTemplate,
            FundQuoteRefreshService fundQuoteRefreshService
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.fundQuoteRefreshService = fundQuoteRefreshService;
    }

    /**
     * 获取最新官方净值，按 Redis -> DB -> 官方回填 顺序兜底。
     */
    public NavPoint loadLatest(String fundCode) {
        NavPoint cached = loadLatestFromRedis(fundCode);
        if (cached != null) {
            return cached;
        }

        NavPoint dbValue = loadLatestFromDb(fundCode);
        if (dbValue != null) {
            cacheNavPoint(fundCode, dbValue);
            return dbValue;
        }

        syncFromOfficial(fundCode, LocalDate.now(CHINA_ZONE).minusDays(120), LocalDate.now(CHINA_ZONE));
        NavPoint synced = loadLatestFromDb(fundCode);
        if (synced != null) {
            cacheNavPoint(fundCode, synced);
        }
        return synced;
    }

    /**
     * 获取某日（含）之前最近一条官方净值。
     */
    public NavPoint loadOnOrBefore(String fundCode, LocalDate targetDate) {
        if (targetDate == null) {
            return loadLatest(fundCode);
        }
        NavPoint exactCached = loadDayFromRedis(fundCode, targetDate);
        if (exactCached != null) {
            return exactCached;
        }

        NavPoint dbValue = loadOnOrBeforeFromDb(fundCode, targetDate);
        if (dbValue != null) {
            cacheNavPoint(fundCode, dbValue);
            return dbValue;
        }

        syncFromOfficial(fundCode, targetDate.minusDays(120), targetDate.plusDays(5));
        NavPoint synced = loadOnOrBeforeFromDb(fundCode, targetDate);
        if (synced != null) {
            cacheNavPoint(fundCode, synced);
        }
        return synced;
    }

    /**
     * 获取某日（含）之后第一条官方净值。
     */
    public NavPoint loadOnOrAfter(String fundCode, LocalDate targetDate) {
        if (targetDate == null) {
            return loadLatest(fundCode);
        }
        NavPoint exactCached = loadDayFromRedis(fundCode, targetDate);
        if (exactCached != null) {
            return exactCached;
        }

        NavPoint dbValue = loadOnOrAfterFromDb(fundCode, targetDate);
        if (dbValue != null) {
            cacheNavPoint(fundCode, dbValue);
            return dbValue;
        }

        syncFromOfficial(fundCode, targetDate.minusDays(15), targetDate.plusDays(15));
        NavPoint synced = loadOnOrAfterFromDb(fundCode, targetDate);
        if (synced != null) {
            cacheNavPoint(fundCode, synced);
        }
        return synced;
    }

    /**
     * 读取历史净值区间：先 Redis，后 DB，最后官方补数。
     */
    public List<FundDtos.HistoryPointResponse> loadHistoryRange(String fundCode, LocalDate startDate, LocalDate endDate) {
        String rangeKey = rangeKey(fundCode, startDate, endDate);
        List<FundDtos.HistoryPointResponse> cached = loadRangeFromRedis(rangeKey);
        if (!cached.isEmpty()) {
            return cached;
        }

        List<FundDtos.HistoryPointResponse> dbPoints = loadRangeFromDb(fundCode, startDate, endDate);
        if (needOfficialSync(dbPoints, endDate)) {
            syncFromOfficial(fundCode, startDate, endDate);
            dbPoints = loadRangeFromDb(fundCode, startDate, endDate);
        }

        cacheRange(rangeKey, dbPoints);
        for (FundDtos.HistoryPointResponse point : dbPoints) {
            cacheNavPoint(fundCode, new NavPoint(fundCode, point.navDate(), point.unitNav(), point.dailyReturnPct()));
        }
        return dbPoints;
    }

    /**
     * 每次增量同步一批基金日净值。
     * 在 2 核 2G 机器上采用分批策略，避免一次性全量抓取拖垮实例。
     */
    public int syncAllFundsNavBatch(int batchSize) {
        int safeBatch = Math.max(50, Math.min(batchSize, 2000));
        long cursor = readSyncCursor();
        List<ShareClassCursor> rows = jdbcTemplate.query("""
                SELECT id, fund_code
                FROM fund_share_class
                WHERE status = 'ACTIVE' AND id > :cursor
                ORDER BY id
                LIMIT :batchSize
                """, new MapSqlParameterSource()
                .addValue("cursor", cursor)
                .addValue("batchSize", safeBatch), (rs, rowNum) -> new ShareClassCursor(
                rs.getLong("id"),
                rs.getString("fund_code")
        ));
        if (rows.isEmpty()) {
            writeSyncCursor(0L);
            return 0;
        }

        int synced = 0;
        LocalDate today = LocalDate.now(CHINA_ZONE);
        for (ShareClassCursor row : rows) {
            try {
                syncFromOfficial(row.fundCode(), today.minusDays(30), today);
                NavPoint latest = loadLatestFromDb(row.fundCode());
                if (latest != null) {
                    cacheNavPoint(row.fundCode(), latest);
                }
                synced += 1;
            } catch (Exception exception) {
                log.warn("Sync daily NAV failed for fund {}", row.fundCode(), exception);
            }
        }
        writeSyncCursor(rows.get(rows.size() - 1).id());
        return synced;
    }

    private boolean needOfficialSync(List<FundDtos.HistoryPointResponse> points, LocalDate endDate) {
        if (points.isEmpty()) {
            return true;
        }
        if (endDate == null) {
            return false;
        }
        LocalDate lastDate = points.get(points.size() - 1).navDate();
        if (lastDate == null) {
            return true;
        }
        LocalDate today = LocalDate.now(CHINA_ZONE);
        if (endDate.isAfter(today.minusDays(1))) {
            // 当请求包含今天时，允许最新一条到昨天（官方日净值通常 T+1 披露）
            return lastDate.isBefore(today.minusDays(1));
        }
        return lastDate.isBefore(endDate);
    }

    private void syncFromOfficial(String fundCode, LocalDate startDate, LocalDate endDate) {
        try {
            fundQuoteRefreshService.syncFundNavHistory(fundCode, startDate, endDate);
        } catch (Exception exception) {
            log.warn("Sync fund nav history failed: fundCode={}, startDate={}, endDate={}", fundCode, startDate, endDate, exception);
        }
    }

    private NavPoint loadLatestFromRedis(String fundCode) {
        if (!StringUtils.hasText(fundCode)) {
            return null;
        }
        return readNavPoint(latestKey(fundCode));
    }

    private NavPoint loadDayFromRedis(String fundCode, LocalDate navDate) {
        if (!StringUtils.hasText(fundCode) || navDate == null) {
            return null;
        }
        return readNavPoint(dayKey(fundCode, navDate));
    }

    private List<FundDtos.HistoryPointResponse> loadRangeFromRedis(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(value)) {
                return List.of();
            }
            return objectMapper.readValue(value, new TypeReference<List<FundDtos.HistoryPointResponse>>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private NavPoint loadLatestFromDb(String fundCode) {
        List<NavPoint> rows = jdbcTemplate.query("""
                SELECT fund_code, nav_date, unit_nav, daily_return_pct
                FROM fund_nav_daily
                WHERE fund_code = :fundCode
                  AND unit_nav IS NOT NULL
                ORDER BY nav_date DESC
                LIMIT 1
                """, Map.of("fundCode", fundCode), this::mapNavPoint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private NavPoint loadOnOrBeforeFromDb(String fundCode, LocalDate targetDate) {
        List<NavPoint> rows = jdbcTemplate.query("""
                SELECT fund_code, nav_date, unit_nav, daily_return_pct
                FROM fund_nav_daily
                WHERE fund_code = :fundCode
                  AND unit_nav IS NOT NULL
                  AND nav_date <= :targetDate
                ORDER BY nav_date DESC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("targetDate", targetDate), this::mapNavPoint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private NavPoint loadOnOrAfterFromDb(String fundCode, LocalDate targetDate) {
        List<NavPoint> rows = jdbcTemplate.query("""
                SELECT fund_code, nav_date, unit_nav, daily_return_pct
                FROM fund_nav_daily
                WHERE fund_code = :fundCode
                  AND unit_nav IS NOT NULL
                  AND nav_date >= :targetDate
                ORDER BY nav_date ASC
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("targetDate", targetDate), this::mapNavPoint);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<FundDtos.HistoryPointResponse> loadRangeFromDb(String fundCode, LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query("""
                SELECT nav_date, unit_nav, accumulated_nav, daily_return_pct
                FROM fund_nav_daily
                WHERE fund_code = :fundCode
                  AND nav_date BETWEEN :startDate AND :endDate
                ORDER BY nav_date
                """, new MapSqlParameterSource()
                .addValue("fundCode", fundCode)
                .addValue("startDate", startDate)
                .addValue("endDate", endDate), (rs, rowNum) -> new FundDtos.HistoryPointResponse(
                toLocalDate(rs.getDate("nav_date")),
                rs.getBigDecimal("unit_nav"),
                rs.getBigDecimal("accumulated_nav"),
                rs.getBigDecimal("daily_return_pct")
        ));
    }

    private NavPoint mapNavPoint(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new NavPoint(
                rs.getString("fund_code"),
                toLocalDate(rs.getDate("nav_date")),
                rs.getBigDecimal("unit_nav"),
                rs.getBigDecimal("daily_return_pct")
        );
    }

    private void cacheNavPoint(String fundCode, NavPoint navPoint) {
        if (!StringUtils.hasText(fundCode) || navPoint == null || navPoint.navDate() == null) {
            return;
        }
        writeValue(dayKey(fundCode, navPoint.navDate()), navPoint, DAY_CACHE_TTL);
        writeValue(latestKey(fundCode), navPoint, DAY_CACHE_TTL);
    }

    private void cacheRange(String key, List<FundDtos.HistoryPointResponse> points) {
        writeValue(key, points == null ? List.of() : points, RANGE_CACHE_TTL);
    }

    private NavPoint readNavPoint(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(value)) {
                return null;
            }
            return objectMapper.readValue(value, NavPoint.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private void writeValue(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
            // 缓存失败不影响主流程
        }
    }

    private long readSyncCursor() {
        try {
            String raw = redisTemplate.opsForValue().get(NAV_SYNC_CURSOR_KEY);
            if (!StringUtils.hasText(raw)) {
                return 0L;
            }
            return Long.parseLong(raw.trim());
        } catch (Exception exception) {
            return 0L;
        }
    }

    private void writeSyncCursor(long cursor) {
        try {
            redisTemplate.opsForValue().set(NAV_SYNC_CURSOR_KEY, String.valueOf(Math.max(cursor, 0L)), Duration.ofDays(30));
        } catch (Exception ignored) {
            // ignore
        }
    }

    private String latestKey(String fundCode) {
        return PREFIX + "latest:" + fundCode;
    }

    private String dayKey(String fundCode, LocalDate navDate) {
        return PREFIX + "day:" + fundCode + ":" + navDate;
    }

    private String rangeKey(String fundCode, LocalDate startDate, LocalDate endDate) {
        return PREFIX + "range:" + fundCode + ":" + startDate + ":" + endDate;
    }

    private LocalDate toLocalDate(Date value) {
        return value == null ? null : value.toLocalDate();
    }

    public record NavPoint(
            String fundCode,
            LocalDate navDate,
            BigDecimal unitNav,
            BigDecimal dailyReturnPct
    ) {
    }

    private record ShareClassCursor(
            long id,
            String fundCode
    ) {
    }
}
