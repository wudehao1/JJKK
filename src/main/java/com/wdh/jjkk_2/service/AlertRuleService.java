package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.common.BusinessException;
import com.wdh.jjkk_2.dto.AlertRuleDtos;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AlertRuleService {
    private static final Logger log = LoggerFactory.getLogger(AlertRuleService.class);
    private static final List<String> RULE_KEYS = List.of("DAILY_RISE", "DAILY_FALL", "NAV_RISE", "NAV_FALL");
    private static final Set<String> REMIND_MODES = Set.of("IMMEDIATE", "AT_1430", "CUSTOM");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AlertRuleService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureAlertRuleTable() {
        try {
            jdbcTemplate.getJdbcTemplate().execute("""
                    CREATE TABLE IF NOT EXISTS user_alert_rule (
                      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      user_id BIGINT UNSIGNED NOT NULL,
                      fund_code CHAR(6) NOT NULL,
                      rule_type ENUM('ESTIMATE_RETURN','OFFICIAL_RETURN','NAV','PROFIT_LOSS','DATA_DELAY') NOT NULL,
                      compare_op ENUM('GT','GTE','LT','LTE','EQ') NOT NULL,
                      threshold_value DECIMAL(18,6) NOT NULL,
                      enabled TINYINT(1) NOT NULL DEFAULT 1,
                      remind_mode ENUM('IMMEDIATE','AT_1430','CUSTOM') NOT NULL DEFAULT 'IMMEDIATE',
                      remind_time TIME DEFAULT NULL,
                      last_triggered_at DATETIME(3) DEFAULT NULL,
                      created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                      PRIMARY KEY (id),
                      KEY idx_alert_user_fund (user_id, fund_code, enabled),
                      KEY idx_alert_fund (fund_code),
                      CONSTRAINT fk_alert_user FOREIGN KEY (user_id) REFERENCES app_user (id),
                      CONSTRAINT fk_alert_share FOREIGN KEY (fund_code) REFERENCES fund_share_class (fund_code)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            ensureColumn("remind_mode", "ALTER TABLE user_alert_rule ADD COLUMN remind_mode ENUM('IMMEDIATE','AT_1430','CUSTOM') NOT NULL DEFAULT 'IMMEDIATE' AFTER enabled");
            ensureColumn("remind_time", "ALTER TABLE user_alert_rule ADD COLUMN remind_time TIME DEFAULT NULL AFTER remind_mode");
        } catch (Exception exception) {
            log.warn("Ensure alert rule table failed, please run schema update manually", exception);
        }
    }

    public AlertRuleDtos.AlertSettingResponse getSettings(Long userId, String fundCode) {
        assertUserAndFund(userId, fundCode);
        List<StoredRule> stored = jdbcTemplate.query("""
                SELECT rule_type, compare_op, threshold_value, remind_mode, remind_time, updated_at
                FROM user_alert_rule
                WHERE user_id = :userId AND fund_code = :fundCode AND enabled = 1
                ORDER BY id
                """, Map.of("userId", userId, "fundCode", fundCode), (rs, rowNum) -> new StoredRule(
                rs.getString("rule_type"),
                rs.getString("compare_op"),
                rs.getBigDecimal("threshold_value"),
                rs.getString("remind_mode"),
                rs.getTime("remind_time") == null ? null : rs.getTime("remind_time").toLocalTime(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime()
        ));

        Map<String, AlertRuleDtos.RuleResponse> rules = defaultRules();
        String remindMode = "IMMEDIATE";
        String customTime = null;
        LocalDateTime updatedAt = null;
        for (StoredRule row : stored) {
            String key = toRuleKey(row.ruleType(), row.compareOp());
            if (key != null) {
                BigDecimal threshold = "DAILY_FALL".equals(key) ? row.threshold().abs() : row.threshold();
                rules.put(key, new AlertRuleDtos.RuleResponse(key, true, threshold));
            }
            if (REMIND_MODES.contains(row.remindMode())) {
                remindMode = row.remindMode();
            }
            if (row.remindTime() != null) {
                customTime = row.remindTime().format(TIME_FORMAT);
            }
            if (row.updatedAt() != null && (updatedAt == null || row.updatedAt().isAfter(updatedAt))) {
                updatedAt = row.updatedAt();
            }
        }
        return new AlertRuleDtos.AlertSettingResponse(fundCode, remindMode, customTime, new ArrayList<>(rules.values()), updatedAt);
    }

    @Transactional
    public AlertRuleDtos.AlertSettingResponse saveSettings(
            Long userId,
            String fundCode,
            AlertRuleDtos.AlertSettingRequest request
    ) {
        assertUserAndFund(userId, fundCode);
        String remindMode = normalizeRemindMode(request.remindMode());
        LocalTime remindTime = resolveRemindTime(remindMode, request.customTime());

        Map<String, AlertRuleDtos.RuleRequest> requested = new LinkedHashMap<>();
        for (AlertRuleDtos.RuleRequest rule : request.rules()) {
            if (rule != null && RULE_KEYS.contains(rule.ruleKey())) {
                requested.put(rule.ruleKey(), rule);
            }
        }

        List<RuleDefinition> enabledRules = new ArrayList<>();
        for (String key : RULE_KEYS) {
            AlertRuleDtos.RuleRequest rule = requested.get(key);
            if (rule == null || !Boolean.TRUE.equals(rule.enabled())) {
                continue;
            }
            if (rule.threshold() == null || rule.threshold().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(key + " 的提醒值必须大于 0");
            }
            enabledRules.add(toDefinition(key, rule.threshold()));
        }
        if (enabledRules.isEmpty()) {
            throw new BusinessException("请至少开启一个涨跌幅或净值提醒");
        }

        jdbcTemplate.update("""
                DELETE FROM user_alert_rule
                WHERE user_id = :userId AND fund_code = :fundCode
                """, Map.of("userId", userId, "fundCode", fundCode));
        for (RuleDefinition rule : enabledRules) {
            jdbcTemplate.update("""
                    INSERT INTO user_alert_rule (
                      user_id, fund_code, rule_type, compare_op, threshold_value,
                      enabled, remind_mode, remind_time
                    )
                    VALUES (
                      :userId, :fundCode, :ruleType, :compareOp, :threshold,
                      1, :remindMode, :remindTime
                    )
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("fundCode", fundCode)
                    .addValue("ruleType", rule.ruleType())
                    .addValue("compareOp", rule.compareOp())
                    .addValue("threshold", rule.threshold())
                    .addValue("remindMode", remindMode)
                    .addValue("remindTime", remindTime == null ? null : Time.valueOf(remindTime)));
        }
        jdbcTemplate.update("""
                UPDATE user_watchlist
                SET alert_enabled = 1, updated_at = CURRENT_TIMESTAMP(3)
                WHERE user_id = :userId AND fund_code = :fundCode
                """, Map.of("userId", userId, "fundCode", fundCode));
        return getSettings(userId, fundCode);
    }

    private void ensureColumn(String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'user_alert_rule'
                  AND column_name = :columnName
                """, Map.of("columnName", columnName), Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.getJdbcTemplate().execute(ddl);
        }
    }

    private Map<String, AlertRuleDtos.RuleResponse> defaultRules() {
        Map<String, AlertRuleDtos.RuleResponse> rules = new LinkedHashMap<>();
        for (String key : RULE_KEYS) {
            rules.put(key, new AlertRuleDtos.RuleResponse(key, false, null));
        }
        return rules;
    }

    private String normalizeRemindMode(String value) {
        String mode = value == null ? "" : value.trim().toUpperCase();
        if (!REMIND_MODES.contains(mode)) {
            throw new BusinessException("请选择提醒时间");
        }
        return mode;
    }

    private LocalTime resolveRemindTime(String mode, String customTime) {
        if ("AT_1430".equals(mode)) {
            return LocalTime.of(14, 30);
        }
        if (!"CUSTOM".equals(mode)) {
            return null;
        }
        try {
            return LocalTime.parse(customTime, TIME_FORMAT);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new BusinessException("请选择有效的自定义提醒时间");
        }
    }

    private RuleDefinition toDefinition(String key, BigDecimal threshold) {
        return switch (key) {
            case "DAILY_RISE" -> new RuleDefinition("ESTIMATE_RETURN", "GTE", threshold);
            case "DAILY_FALL" -> new RuleDefinition("ESTIMATE_RETURN", "LTE", threshold.negate());
            case "NAV_RISE" -> new RuleDefinition("NAV", "GTE", threshold);
            case "NAV_FALL" -> new RuleDefinition("NAV", "LTE", threshold);
            default -> throw new BusinessException("不支持的提醒条件");
        };
    }

    private String toRuleKey(String ruleType, String compareOp) {
        if ("ESTIMATE_RETURN".equals(ruleType) && "GTE".equals(compareOp)) return "DAILY_RISE";
        if ("ESTIMATE_RETURN".equals(ruleType) && "LTE".equals(compareOp)) return "DAILY_FALL";
        if ("NAV".equals(ruleType) && "GTE".equals(compareOp)) return "NAV_RISE";
        if ("NAV".equals(ruleType) && "LTE".equals(compareOp)) return "NAV_FALL";
        return null;
    }

    private void assertUserAndFund(Long userId, String fundCode) {
        Integer userCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM app_user WHERE id = :userId AND status <> 'DELETED'
                """, Map.of("userId", userId), Integer.class);
        if (userCount == null || userCount == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        Integer fundCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fund_share_class WHERE fund_code = :fundCode AND status <> 'TERMINATED'
                """, Map.of("fundCode", fundCode), Integer.class);
        if (fundCount == null || fundCount == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "基金不存在或已终止");
        }
    }

    private record RuleDefinition(String ruleType, String compareOp, BigDecimal threshold) {
    }

    private record StoredRule(
            String ruleType,
            String compareOp,
            BigDecimal threshold,
            String remindMode,
            LocalTime remindTime,
            LocalDateTime updatedAt
    ) {
    }
}
