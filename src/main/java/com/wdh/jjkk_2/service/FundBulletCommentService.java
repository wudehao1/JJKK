package com.wdh.jjkk_2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.common.BusinessException;
import com.wdh.jjkk_2.dto.BulletCommentDtos;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FundBulletCommentService {
    private static final String KEY_PREFIX = "jjkk:fund:bullet:";
    private static final int MAX_COUNT = 15;
    private static final Duration COMMENT_TTL = Duration.ofMinutes(10);
    private static final Duration KEY_TTL = Duration.ofMinutes(11);
    private static final String DEFAULT_COLOR = "#1677F2";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Map<String, List<StoredBullet>> fallbackStore = new ConcurrentHashMap<>();

    public FundBulletCommentService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public synchronized BulletCommentDtos.ListResponse list(String fundCode) {
        ensureFundExists(fundCode);
        List<StoredBullet> active = activeBullets(fundCode);
        saveBullets(fundCode, active);
        return response(fundCode, active);
    }

    public synchronized BulletCommentDtos.ListResponse send(String fundCode, BulletCommentDtos.SendRequest request) {
        ensureFundExists(fundCode);
        String content = normalizeContent(request.content());
        if (!StringUtils.hasText(content)) {
            throw new BusinessException("弹幕内容不能为空");
        }
        if (content.codePointCount(0, content.length()) > 60) {
            throw new BusinessException("弹幕最多60个字");
        }
        LocalDateTime now = LocalDateTime.now();
        List<StoredBullet> bullets = activeBullets(fundCode);
        bullets.add(new StoredBullet(
                UUID.randomUUID().toString().replace("-", ""),
                fundCode,
                content,
                normalizeColor(request.color()),
                now,
                now.plus(COMMENT_TTL)
        ));
        bullets.sort(Comparator.comparing(StoredBullet::createdAt));
        while (bullets.size() > MAX_COUNT) {
            bullets.remove(0);
        }
        saveBullets(fundCode, bullets);
        return response(fundCode, bullets);
    }

    private List<StoredBullet> activeBullets(String fundCode) {
        LocalDateTime now = LocalDateTime.now();
        List<StoredBullet> bullets = readBullets(fundCode).stream()
                .filter(item -> item.expiresAt() != null && item.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(StoredBullet::createdAt))
                .toList();
        if (bullets.size() <= MAX_COUNT) {
            return new ArrayList<>(bullets);
        }
        return new ArrayList<>(bullets.subList(bullets.size() - MAX_COUNT, bullets.size()));
    }

    private List<StoredBullet> readBullets(String fundCode) {
        try {
            List<String> values = redisTemplate.opsForList().range(redisKey(fundCode), 0, -1);
            if (values == null) {
                return List.of();
            }
            List<StoredBullet> rows = new ArrayList<>();
            for (String value : values) {
                StoredBullet bullet = objectMapper.readValue(value, StoredBullet.class);
                if (bullet != null) {
                    rows.add(bullet);
                }
            }
            return rows;
        } catch (Exception ignored) {
            return fallbackStore.getOrDefault(fundCode, List.of());
        }
    }

    private void saveBullets(String fundCode, List<StoredBullet> bullets) {
        try {
            String key = redisKey(fundCode);
            redisTemplate.delete(key);
            if (!bullets.isEmpty()) {
                List<String> values = new ArrayList<>();
                for (StoredBullet bullet : bullets) {
                    values.add(objectMapper.writeValueAsString(bullet));
                }
                redisTemplate.opsForList().rightPushAll(key, values);
                redisTemplate.expire(key, KEY_TTL);
            }
        } catch (Exception ignored) {
            fallbackStore.put(fundCode, new ArrayList<>(bullets));
        }
    }

    private BulletCommentDtos.ListResponse response(String fundCode, List<StoredBullet> bullets) {
        List<BulletCommentDtos.ItemResponse> items = bullets.stream()
                .map(item -> new BulletCommentDtos.ItemResponse(
                        item.id(),
                        item.fundCode(),
                        item.content(),
                        item.color(),
                        item.createdAt(),
                        item.expiresAt()
                ))
                .toList();
        return new BulletCommentDtos.ListResponse(fundCode, MAX_COUNT, (int) COMMENT_TTL.toSeconds(), items);
    }

    private void ensureFundExists(String fundCode) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM fund_share_class
                WHERE fund_code = :fundCode
                """, Map.of("fundCode", fundCode), Long.class);
        if (count == null || count <= 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "基金不存在");
        }
    }

    private String normalizeContent(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private String normalizeColor(String color) {
        if (!StringUtils.hasText(color)) {
            return DEFAULT_COLOR;
        }
        return color.trim();
    }

    private String redisKey(String fundCode) {
        return KEY_PREFIX + fundCode;
    }

    private record StoredBullet(
            String id,
            String fundCode,
            String content,
            String color,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
    }
}
