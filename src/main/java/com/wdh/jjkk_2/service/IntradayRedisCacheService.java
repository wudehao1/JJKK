package com.wdh.jjkk_2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.dto.FundDtos;
import com.wdh.jjkk_2.dto.MarketDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 当日分时图的 Redis 优先缓存服务。
 *
 * 开盘期间基金和大盘都会持续产生分钟点，如果每分钟都直接读写 MySQL，会让 2 核 2G
 * 服务器压力过高。这里把当天分钟点先放进 Redis hash，图表读取和分钟写入都走内存；
 * 夜间调度再调用 {@link #persistCachedIntraday(LocalDate)} 批量落库，供长期保存和
 * 第二天无实时源时兜底展示。
 */
@Service
public class IntradayRedisCacheService {
    private static final Logger log = LoggerFactory.getLogger(IntradayRedisCacheService.class);
    private static final Duration INTRADAY_TTL = Duration.ofHours(42);
    private static final String PREFIX = "jjkk:intraday:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public IntradayRedisCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean cacheFundDetail(FundDtos.DetailResponse detail) {
        if (detail == null || detail.fundCode() == null) {
            return false;
        }
        return redisWrite(() -> {
            String key = PREFIX + "fund:detail:" + detail.fundCode();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(detail), INTRADAY_TTL);
        });
    }

    public FundDtos.DetailResponse loadFundDetail(String fundCode) {
        try {
            String value = redisTemplate.opsForValue().get(PREFIX + "fund:detail:" + fundCode);
            return value == null ? null : objectMapper.readValue(value, FundDtos.DetailResponse.class);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 缓存单只基金的一个分钟估算点。
     *
     * 数据按“交易日 + 基金代码”写入 Redis hash，同时把基金代码记录到当天集合中。
     * 夜间落库任务会先读取这个集合，再逐只基金取出 hash 内容持久化到 MySQL。
     */
    public boolean cacheFundMinutePoint(String fundCode, LocalDate tradingDay, FundDtos.EstimateMinutePointResponse point) {
        if (fundCode == null || tradingDay == null || point == null || point.quoteTime() == null) {
            return false;
        }
        return redisWrite(() -> {
            String key = fundMinuteKey(tradingDay, fundCode);
            redisTemplate.opsForHash().put(key, point.quoteTime().toString(), objectMapper.writeValueAsString(point));
            redisTemplate.opsForSet().add(fundSetKey(tradingDay), fundCode);
            redisTemplate.opsForValue().set(latestFundDayKey(fundCode), tradingDay.toString(), INTRADAY_TTL);
            redisTemplate.expire(key, INTRADAY_TTL);
            redisTemplate.expire(fundSetKey(tradingDay), INTRADAY_TTL);
        });
    }

    public void cacheFundMinuteResponse(FundDtos.MinuteSeriesResponse response) {
        if (response == null || response.fundCode() == null || response.tradingDay() == null) {
            return;
        }
        for (FundDtos.EstimateMinutePointResponse point : response.points()) {
            cacheFundMinutePoint(response.fundCode(), response.tradingDay(), point);
        }
    }

    public LocalDate latestFundTradingDay(String fundCode) {
        try {
            String value = redisTemplate.opsForValue().get(latestFundDayKey(fundCode));
            return value == null ? null : LocalDate.parse(value);
        } catch (Exception exception) {
            return null;
        }
    }

    public List<FundDtos.EstimateMinutePointResponse> loadFundMinutePoints(String fundCode, LocalDate tradingDay) {
        return loadHashValues(
                fundMinuteKey(tradingDay, fundCode),
                FundDtos.EstimateMinutePointResponse.class,
                Comparator.comparing(FundDtos.EstimateMinutePointResponse::quoteTime)
        );
    }

    public void cacheMarketMinuteResponse(MarketDtos.MinuteSeriesResponse response) {
        if (response == null || response.symbol() == null || response.tradingDay() == null) {
            return;
        }
        redisWrite(() -> {
            String metaKey = marketMetaKey(response.tradingDay(), response.symbol());
            redisTemplate.opsForValue().set(metaKey, objectMapper.writeValueAsString(new MarketMeta(
                    response.market(),
                    response.symbol(),
                    response.name()
            )), INTRADAY_TTL);
            redisTemplate.opsForSet().add(marketSetKey(response.tradingDay()), response.symbol());
            redisTemplate.opsForValue().set(latestMarketDayKey(response.symbol()), response.tradingDay().toString(), INTRADAY_TTL);
            redisTemplate.expire(marketSetKey(response.tradingDay()), INTRADAY_TTL);
        });
        for (MarketDtos.MinutePointResponse point : response.points()) {
            cacheMarketMinutePoint(response.symbol(), response.tradingDay(), point);
        }
    }

    /**
     * 缓存一个大盘品种的分钟行情点。
     *
     * 结构与基金分钟点一致，只是 key 使用市场 symbol。这样 A 股、港股、美股、黄金等
     * 大盘分时都能用同一套缓存和落库流程。
     */
    public boolean cacheMarketMinutePoint(String symbol, LocalDate tradingDay, MarketDtos.MinutePointResponse point) {
        if (symbol == null || tradingDay == null || point == null || point.quoteTime() == null) {
            return false;
        }
        return redisWrite(() -> {
            String key = marketMinuteKey(tradingDay, symbol);
            redisTemplate.opsForHash().put(key, point.quoteTime().toString(), objectMapper.writeValueAsString(point));
            redisTemplate.opsForSet().add(marketSetKey(tradingDay), symbol);
            redisTemplate.opsForValue().set(latestMarketDayKey(symbol), tradingDay.toString(), INTRADAY_TTL);
            redisTemplate.expire(key, INTRADAY_TTL);
            redisTemplate.expire(marketSetKey(tradingDay), INTRADAY_TTL);
        });
    }

    public LocalDate latestMarketTradingDay(String symbol) {
        try {
            String value = redisTemplate.opsForValue().get(latestMarketDayKey(symbol));
            return value == null ? null : LocalDate.parse(value);
        } catch (Exception exception) {
            return null;
        }
    }

    public List<MarketDtos.MinutePointResponse> loadMarketMinutePoints(String symbol, LocalDate tradingDay) {
        return loadHashValues(
                marketMinuteKey(tradingDay, symbol),
                MarketDtos.MinutePointResponse.class,
                Comparator.comparing(MarketDtos.MinutePointResponse::quoteTime)
        );
    }

    /**
     * 把某个交易日 Redis 中的全部分钟点持久化到 MySQL。
     *
     * SQL 使用 upsert，任务失败后重复执行也是安全的，不会插入重复分钟点。
     * 正常情况下该方法由午夜调度触发，把白天内存里的高频图表数据转成长期存储。
     */
    public void persistCachedIntraday(LocalDate tradingDay) {
        persistFundMinutes(tradingDay);
        persistMarketMinutes(tradingDay);
    }

    private void persistFundMinutes(LocalDate tradingDay) {
        Set<String> fundCodes = safeMembers(fundSetKey(tradingDay));
        if (fundCodes.isEmpty()) {
            return;
        }
        int sourceId = ensureDataSource();
        for (String fundCode : fundCodes) {
            List<FundDtos.EstimateMinutePointResponse> points = loadFundMinutePoints(fundCode, tradingDay);
            for (FundDtos.EstimateMinutePointResponse point : points) {
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
        }
    }

    private void persistMarketMinutes(LocalDate tradingDay) {
        Set<String> symbols = safeMembers(marketSetKey(tradingDay));
        if (symbols.isEmpty()) {
            return;
        }
        int sourceId = ensureDataSource();
        for (String symbol : symbols) {
            MarketMeta meta = loadMarketMeta(tradingDay, symbol);
            if (meta == null) {
                continue;
            }
            long instrumentId = ensureInstrument(meta, sourceId);
            List<MarketDtos.MinutePointResponse> points = loadMarketMinutePoints(symbol, tradingDay);
            for (MarketDtos.MinutePointResponse point : points) {
                jdbcTemplate.update("""
                        INSERT INTO market_quote_minute (
                          instrument_id, trading_day, quote_time, open_price, high_price, low_price,
                          close_price, prev_close, change_pct, volume, turnover, source_id
                        ) VALUES (
                          :instrumentId, :tradingDay, :quoteTime, NULL, NULL, NULL,
                          :closePrice, NULL, :changePct, :volume, :turnover, :sourceId
                        )
                        ON DUPLICATE KEY UPDATE
                          close_price = VALUES(close_price),
                          change_pct = VALUES(change_pct),
                          volume = VALUES(volume),
                          turnover = VALUES(turnover),
                          source_id = VALUES(source_id)
                        """, new MapSqlParameterSource()
                        .addValue("instrumentId", instrumentId)
                        .addValue("tradingDay", tradingDay)
                        .addValue("quoteTime", point.quoteTime())
                        .addValue("closePrice", point.price())
                        .addValue("changePct", point.changePct())
                        .addValue("volume", point.volume())
                        .addValue("turnover", point.turnover())
                        .addValue("sourceId", sourceId));
            }
        }
    }

    private MarketMeta loadMarketMeta(LocalDate tradingDay, String symbol) {
        try {
            String value = redisTemplate.opsForValue().get(marketMetaKey(tradingDay, symbol));
            return value == null ? null : objectMapper.readValue(value, MarketMeta.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private long ensureInstrument(MarketMeta meta, int sourceId) {
        jdbcTemplate.update("""
                INSERT INTO security_instrument (
                  market, symbol, instrument_name, instrument_type, currency, exchange_code,
                  enabled, source_id, source_updated_at
                ) VALUES (
                  :market, :symbol, :name, 'INDEX', 'CNY', :market,
                  1, :sourceId, CURRENT_TIMESTAMP(3)
                )
                ON DUPLICATE KEY UPDATE
                  instrument_name = VALUES(instrument_name),
                  instrument_type = 'INDEX',
                  enabled = 1,
                  source_id = VALUES(source_id),
                  source_updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("market", meta.market())
                .addValue("symbol", meta.symbol())
                .addValue("name", meta.name())
                .addValue("sourceId", sourceId));
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                SELECT id
                FROM security_instrument
                WHERE market = :market AND symbol = :symbol
                """, Map.of("market", meta.market(), "symbol", meta.symbol()), Long.class));
    }

    private int ensureDataSource() {
        List<Integer> ids = jdbcTemplate.query("""
                SELECT id
                FROM data_source
                WHERE source_code = 'REDIS_INTRADAY_CACHE'
                LIMIT 1
                """, (rs, rowNum) -> rs.getInt("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO data_source (
                  source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark
                ) VALUES (
                  'REDIS_INTRADAY_CACHE', 'Redis intraday cache', 'THIRD_PARTY', 4, 'INTERNAL', 'redis://local', 40, 1,
                  'User-viewed intraday quotes are cached in Redis and persisted by the midnight job.'
                )
                """, new MapSqlParameterSource(), keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).intValue();
    }

    private Set<String> safeMembers(String key) {
        try {
            Set<String> members = redisTemplate.opsForSet().members(key);
            return members == null ? Set.of() : members;
        } catch (Exception exception) {
            return Set.of();
        }
    }

    private <T> List<T> loadHashValues(String key, Class<T> type, Comparator<T> comparator) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            if (entries.isEmpty()) {
                return List.of();
            }
            List<T> values = new ArrayList<>();
            for (Object value : entries.values()) {
                if (value != null) {
                    values.add(objectMapper.readValue(String.valueOf(value), type));
                }
            }
            values.sort(comparator);
            return values;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private boolean redisWrite(RedisWriter writer) {
        try {
            writer.write();
            return true;
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Redis intraday cache write failed", exception);
            return false;
        }
    }

    private String fundMinuteKey(LocalDate tradingDay, String fundCode) {
        return PREFIX + tradingDay + ":fund:" + fundCode + ":minute";
    }

    private String fundSetKey(LocalDate tradingDay) {
        return PREFIX + tradingDay + ":funds";
    }

    private String latestFundDayKey(String fundCode) {
        return PREFIX + "fund:" + fundCode + ":latest-day";
    }

    private String marketMinuteKey(LocalDate tradingDay, String symbol) {
        return PREFIX + tradingDay + ":market:" + symbol + ":minute";
    }

    private String marketMetaKey(LocalDate tradingDay, String symbol) {
        return PREFIX + tradingDay + ":market:" + symbol + ":meta";
    }

    private String marketSetKey(LocalDate tradingDay) {
        return PREFIX + tradingDay + ":markets";
    }

    private String latestMarketDayKey(String symbol) {
        return PREFIX + "market:" + symbol + ":latest-day";
    }

    @FunctionalInterface
    private interface RedisWriter {
        void write() throws JsonProcessingException;
    }

    private record MarketMeta(String market, String symbol, String name) {
    }
}

