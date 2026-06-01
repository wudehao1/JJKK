package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.dto.InformationDtos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.common.BusinessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 信息流抓取、清洗、保存和读取服务。
 *
 * 服务会定期拉取东方财富快讯，提取标题、摘要、发布时间、图片和关联标的等信息，
 * 再根据关键词把基金、港股、美股、黄金、利率、监管、暴涨暴跌等内容标记为重要。
 * 本地只保留近三天数据，供小程序信息板块分页懒加载，既保证内容新鲜，也控制小库表体积。
 */
@Service
public class InformationService {
    private static final String SOURCE_CODE = "EASTMONEY_FAST_NEWS";
    private static final String SOURCE_NAME = "\u4e1c\u65b9\u8d22\u5bcc\u5feb\u8baf";
    private static final String EASTMONEY_FAST_NEWS_URL =
            "http://np-listapi.eastmoney.com/comm/web/getFastNewsList?client=web&biz=web_quotekuaixun&fastColumn=102&pageSize=30&sortEnd=&req_trace=jjkk";
    private static final DateTimeFormatter SHOW_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    private static final List<String> IMPORTANT_KEYWORDS = List.of(
            "\u57fa\u91d1", "ETF", "\u6e2f\u80a1", "\u7f8e\u80a1", "\u9ec4\u91d1", "\u503a\u5238", "\u5229\u7387", "\u964d\u606f", "\u52a0\u606f", "\u592e\u884c", "\u653f\u7b56", "\u98ce\u9669",
            "\u66b4\u8dcc", "\u66b4\u6da8", "\u6682\u505c", "\u6e05\u76d8", "\u5206\u7ea2", "\u9650\u8d2d", "\u7533\u8d2d", "\u8d4e\u56de", "\u76d1\u7ba1", "\u91cd\u8981", "\u7a81\u53d1"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile LocalDateTime lastRefreshAt;

    public InformationService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(6000);
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                .defaultHeader("Referer", "https://kuaixun.eastmoney.com/")
                .build();
    }

    @PostConstruct
    public void init() {
        ensureSchema();
        cleanupOld();
        refreshLatestIfNeeded();
    }

    /**
     * 返回信息流列表页需要的卡片数据。
     *
     * 每次读取前会尝试做轻量刷新，测试人员打开页面时通常能看到较新的快讯；
     * page 和 size 会做边界限制，避免前端误传过大分页导致接口一次性读取太多数据。
     */
    public InformationDtos.ListResponse list(boolean importantOnly, int page, int size) {
        refreshLatestIfNeeded();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 20);
        String where = importantOnly ? "WHERE importance = 'IMPORTANT'" : "";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safeSize)
                .addValue("offset", (safePage - 1) * safeSize);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_item " + where, params, Long.class);
        List<InformationDtos.ItemResponse> items = jdbcTemplate.query("""
                SELECT id, title, summary, source_name, source_url, importance, publish_time,
                       image_urls, chart_urls, symbols
                FROM information_item
                """ + where + """
                ORDER BY publish_time DESC, id DESC
                LIMIT :limit OFFSET :offset
                """, params, (rs, rowNum) -> new InformationDtos.ItemResponse(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("source_name"),
                rs.getString("source_url"),
                rs.getString("importance"),
                localDateTime(rs, "publish_time"),
                readStringList(rs.getString("image_urls")),
                readStringList(rs.getString("chart_urls")),
                readStringList(rs.getString("symbols"))
        ));
        long count = total == null ? 0 : total;
        return new InformationDtos.ListResponse(items, safePage, safeSize, count, (long) safePage * safeSize < count);
    }

    public InformationDtos.DetailResponse detail(Long id) {
        refreshLatestIfNeeded();
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, title, summary, content, source_name, source_url, importance, publish_time,
                           image_urls, chart_urls, symbols
                    FROM information_item
                    WHERE id = :id
                    """, Map.of("id", id), (rs, rowNum) -> new InformationDtos.DetailResponse(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("summary"),
                    rs.getString("content"),
                    rs.getString("source_name"),
                    rs.getString("source_url"),
                    rs.getString("importance"),
                    localDateTime(rs, "publish_time"),
                    readStringList(rs.getString("image_urls")),
                    readStringList(rs.getString("chart_urls")),
                    readStringList(rs.getString("symbols"))
            ));
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u8d44\u8baf\u4e0d\u5b58\u5728");
        }
    }

    /**
     * 主动拉取最新上游资讯，并按上游 source_item_id 做幂等写入。
     *
     * 同一条快讯重复抓到时不会插入多条，而是更新标题、摘要、重要性、媒体列表等字段；
     * 抓取结束后顺手清理三天前的数据，保证信息板块不会无限增长。
     */
    public void refreshLatest() {
        ensureDataSource();
        for (InformationDtos.ExternalNewsItem item : fetchEastmoneyFastNews()) {
            upsert(item);
        }
        cleanupOld();
        lastRefreshAt = LocalDateTime.now();
    }

    public void cleanupOld() {
        jdbcTemplate.update("""
                DELETE FROM information_item
                WHERE publish_time < :expireTime
                """, Map.of("expireTime", LocalDateTime.now().minusDays(3)));
    }

    private void refreshLatestIfNeeded() {
        if (lastRefreshAt == null || Duration.between(lastRefreshAt, LocalDateTime.now()).compareTo(REFRESH_INTERVAL) > 0) {
            try {
                refreshLatest();
            } catch (Exception ignored) {
                lastRefreshAt = LocalDateTime.now();
            }
        }
    }

    private List<InformationDtos.ExternalNewsItem> fetchEastmoneyFastNews() {
        try {
            JsonNode list = objectMapper.readTree(restClient.get().uri(EASTMONEY_FAST_NEWS_URL).retrieve().body(String.class))
                    .path("data")
                    .path("fastNewsList");
            if (!list.isArray()) {
                return List.of();
            }
            List<InformationDtos.ExternalNewsItem> items = new ArrayList<>();
            for (JsonNode node : list) {
                String code = node.path("code").asText("");
                String title = node.path("title").asText("");
                String summary = node.path("summary").asText("");
                LocalDateTime publishTime = parseShowTime(node.path("showTime").asText(null));
                List<String> images = textArray(node.path("image"));
                List<String> symbols = textArray(node.path("stockList"));
                boolean important = node.path("titleColor").asInt(0) > 0 || isImportant(title + " " + summary);
                items.add(new InformationDtos.ExternalNewsItem(
                        code,
                        title,
                        summary,
                        summary,
                        "https://kuaixun.eastmoney.com/",
                        publishTime,
                        images,
                        List.of(),
                        symbols,
                        important
                ));
            }
            return items;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private void upsert(InformationDtos.ExternalNewsItem item) {
        if (item.sourceItemId() == null || item.sourceItemId().isBlank() || item.title() == null || item.title().isBlank()) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO information_item (
                  source_code, source_item_id, title, summary, content, source_name, source_url,
                  importance, publish_time, image_urls, chart_urls, symbols
                ) VALUES (
                  :sourceCode, :sourceItemId, :title, :summary, :content, :sourceName, :sourceUrl,
                  :importance, :publishTime, :imageUrls, :chartUrls, :symbols
                )
                ON DUPLICATE KEY UPDATE
                  title = VALUES(title),
                  summary = VALUES(summary),
                  content = VALUES(content),
                  source_name = VALUES(source_name),
                  source_url = VALUES(source_url),
                  importance = VALUES(importance),
                  publish_time = VALUES(publish_time),
                  image_urls = VALUES(image_urls),
                  chart_urls = VALUES(chart_urls),
                  symbols = VALUES(symbols),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("sourceCode", SOURCE_CODE)
                .addValue("sourceItemId", item.sourceItemId())
                .addValue("title", item.title())
                .addValue("summary", item.summary())
                .addValue("content", item.content())
                .addValue("sourceName", SOURCE_NAME)
                .addValue("sourceUrl", item.sourceUrl())
                .addValue("importance", item.important() ? "IMPORTANT" : "NORMAL")
                .addValue("publishTime", item.publishTime() == null ? LocalDateTime.now() : item.publishTime())
                .addValue("imageUrls", writeJson(item.imageUrls()))
                .addValue("chartUrls", writeJson(item.chartUrls()))
                .addValue("symbols", writeJson(item.symbols())));
    }

    private void ensureSchema() {
        jdbcTemplate.getJdbcTemplate().execute("""
                CREATE TABLE IF NOT EXISTS information_item (
                  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                  source_code VARCHAR(64) NOT NULL,
                  source_item_id VARCHAR(128) NOT NULL,
                  title VARCHAR(255) NOT NULL,
                  summary TEXT DEFAULT NULL,
                  content MEDIUMTEXT DEFAULT NULL,
                  source_name VARCHAR(64) DEFAULT NULL,
                  source_url VARCHAR(512) DEFAULT NULL,
                  importance ENUM('NORMAL','IMPORTANT') NOT NULL DEFAULT 'NORMAL',
                  publish_time DATETIME(3) NOT NULL,
                  image_urls JSON DEFAULT NULL,
                  chart_urls JSON DEFAULT NULL,
                  symbols JSON DEFAULT NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_information_source_item (source_code, source_item_id),
                  KEY idx_information_time (publish_time),
                  KEY idx_information_importance_time (importance, publish_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='information feed items'
                """);
    }

    private void ensureDataSource() {
        jdbcTemplate.update("""
                INSERT INTO data_source (source_code, source_name, source_type, trust_level, license_status, base_url, priority, enabled, remark)
                VALUES ('EASTMONEY_FAST_NEWS', 'Eastmoney fast news', 'THIRD_PARTY', 3, 'UNKNOWN', 'https://kuaixun.eastmoney.com/', 60, 1, 'Fast news source for the information feed')
                ON DUPLICATE KEY UPDATE
                  source_name = VALUES(source_name),
                  base_url = VALUES(base_url),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource());
    }

    private boolean isImportant(String text) {
        String value = text == null ? "" : text;
        return IMPORTANT_KEYWORDS.stream().anyMatch(value::contains);
    }

    private LocalDateTime parseShowTime(String value) {
        try {
            return LocalDateTime.parse(value, SHOW_TIME_FORMATTER);
        } catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }

    private List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            if (!root.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : root) {
                String text = item.asText("");
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
