package com.wdh.jjkk_2.service;

import com.wdh.jjkk_2.dto.UserDtos;

import com.wdh.jjkk_2.common.BusinessException;
import com.wdh.jjkk_2.mapper.UserFundRowMapper;
import com.wdh.jjkk_2.service.FundQuoteRefreshService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户资料、自选基金和模拟持有交易业务服务。
 *
 * Controller 负责 token 校验与用户隔离，本服务负责数据读写、排序、交易和行情补全。
 */
@Service
public class UserFundService {
    private static final Logger log = LoggerFactory.getLogger(UserFundService.class);
    private static final Set<String> HOLDING_STATUS = Set.of("ACTIVE", "CLEARED", "DELETED");
    private static final Set<String> TRADE_TXN_TYPE = Set.of("BUY", "SELL");
    private static final Set<String> NAV_OPTION = Set.of("AUTO", "TODAY", "YESTERDAY", "TOMORROW");
    private static final int DEFAULT_TRADE_LIMIT = 50;
    private static final int MAX_TRADE_LIMIT = 200;
    private static final String TRADE_STATUS_EXECUTED = "EXECUTED";
    private static final String TRADE_STATUS_PENDING = "PENDING";
    private static final ZoneId CN_MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime CN_MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime CN_MARKET_CLOSE = LocalTime.of(15, 0);
    private static final String DEFAULT_ACCOUNT_NAME = "默认账户";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final FundQuoteRefreshService fundQuoteRefreshService;
    private final OfficialFundImportService officialFundImportService;
    private final FundNavCacheService fundNavCacheService;

    public UserFundService(
            NamedParameterJdbcTemplate jdbcTemplate,
            FundQuoteRefreshService fundQuoteRefreshService,
            OfficialFundImportService officialFundImportService,
            FundNavCacheService fundNavCacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.fundQuoteRefreshService = fundQuoteRefreshService;
        this.officialFundImportService = officialFundImportService;
        this.fundNavCacheService = fundNavCacheService;
    }

    /**
     * 持有模拟交易的“等待官方净值成交”能力依赖这张轻量队列表。
     * 这里在服务启动时做一次幂等建表，避免线上因为漏执行 SQL 而直接报错。
     */
    @PostConstruct
    public void ensurePendingTradeTable() {
        try {
            jdbcTemplate.getJdbcTemplate().execute("""
                    CREATE TABLE IF NOT EXISTS user_holding_pending_trade (
                      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                      user_id BIGINT UNSIGNED NOT NULL,
                      account_id BIGINT UNSIGNED NOT NULL,
                      fund_code CHAR(6) NOT NULL,
                      txn_type ENUM('BUY','SELL') NOT NULL,
                      txn_date DATE NOT NULL,
                      confirm_date DATE DEFAULT NULL,
                      target_nav_date DATE NOT NULL,
                      nav_option ENUM('TODAY','TOMORROW') NOT NULL,
                      amount DECIMAL(24,4) DEFAULT NULL,
                      share DECIMAL(24,4) DEFAULT NULL,
                      fee DECIMAL(18,4) NOT NULL DEFAULT 0,
                      remark VARCHAR(255) DEFAULT NULL,
                      status ENUM('PENDING','PROCESSING','DONE','FAILED') NOT NULL DEFAULT 'PENDING',
                      resolved_nav DECIMAL(18,6) DEFAULT NULL,
                      resolved_nav_date DATE DEFAULT NULL,
                      resolved_txn_id BIGINT UNSIGNED DEFAULT NULL,
                      error_message VARCHAR(255) DEFAULT NULL,
                      created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                      updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                      PRIMARY KEY (id),
                      KEY idx_pending_status_target (status, target_nav_date),
                      KEY idx_pending_user (user_id),
                      KEY idx_pending_fund (fund_code),
                      CONSTRAINT fk_pending_user FOREIGN KEY (user_id) REFERENCES app_user (id),
                      CONSTRAINT fk_pending_account FOREIGN KEY (account_id) REFERENCES user_holding_account (id),
                      CONSTRAINT fk_pending_fund FOREIGN KEY (fund_code) REFERENCES fund_share_class (fund_code)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        } catch (Exception exception) {
            log.warn("Ensure pending trade table failed, please run schema update manually", exception);
        }
    }

    @Transactional
    public UserDtos.UserResponse createOrUpdateUser(UserDtos.CreateUserRequest request) {
        jdbcTemplate.update("""
                INSERT INTO app_user (wechat_openid, wechat_unionid, nickname, avatar_url, status, last_login_at)
                VALUES (:openid, :unionid, :nickname, :avatarUrl, 'ACTIVE', CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE
                  wechat_unionid = COALESCE(VALUES(wechat_unionid), wechat_unionid),
                  nickname = COALESCE(VALUES(nickname), nickname),
                  avatar_url = COALESCE(VALUES(avatar_url), avatar_url),
                  status = 'ACTIVE',
                  last_login_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("openid", request.wechatOpenid().trim())
                .addValue("unionid", trimToNull(request.wechatUnionid()))
                .addValue("nickname", trimToNull(request.nickname()))
                .addValue("avatarUrl", trimToNull(request.avatarUrl())));
        return getUserByOpenid(request.wechatOpenid());
    }

    public UserDtos.UserResponse getUser(Long userId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT id, wechat_openid, wechat_unionid, nickname, avatar_url, status, last_login_at, created_at
                    FROM app_user
                    WHERE id = :userId AND status <> 'DELETED'
                    """, Map.of("userId", userId), UserFundRowMapper.user());
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6570\u636e\u4e0d\u5b58\u5728");
        }
    }

    @Transactional
    public UserDtos.UserResponse updateProfile(Long userId, UserDtos.UserProfileUpdateRequest request) {
        assertUserExists(userId);
        jdbcTemplate.update("""
                UPDATE app_user
                SET nickname = COALESCE(:nickname, nickname),
                    avatar_url = COALESCE(:avatarUrl, avatar_url),
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :userId AND status <> 'DELETED'
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("nickname", trimToNull(request.nickname()))
                .addValue("avatarUrl", trimToNull(request.avatarUrl())));
        return getUser(userId);
    }

    @Transactional
    public UserDtos.UserResponse updateAvatar(Long userId, String avatarUrl) {
        assertUserExists(userId);
        jdbcTemplate.update("""
                UPDATE app_user
                SET avatar_url = :avatarUrl,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :userId AND status <> 'DELETED'
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("avatarUrl", trimToNull(avatarUrl)));
        return getUser(userId);
    }

    /**
     * 返回自选基金列表，并一次性关联基金名称、最新官方净值和估算快照。
     */
    public List<UserDtos.WatchlistItemResponse> listWatchlist(Long userId) {
        assertUserExists(userId);
        return jdbcTemplate.query("""
                SELECT
                  w.id,
                  w.fund_code,
                  fsc.fund_name,
                  fp.fund_type,
                  w.group_name,
                  w.display_order,
                  w.remark,
                  w.alert_enabled,
                  nav.nav_date,
                  nav.unit_nav,
                  nav.daily_return_pct,
                  snap.quote_time,
                  snap.estimate_nav,
                  snap.estimate_return_pct,
                  snap.data_status
                FROM user_watchlist w
                JOIN fund_share_class fsc ON fsc.fund_code = w.fund_code
                JOIN fund_product fp ON fp.id = fsc.product_id
                LEFT JOIN (
                  SELECT n1.fund_code, n1.nav_date, n1.unit_nav, n1.daily_return_pct
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = w.fund_code
                LEFT JOIN fund_realtime_snapshot snap ON snap.fund_code = w.fund_code
                WHERE w.user_id = :userId
                ORDER BY w.display_order, w.id DESC
                """, Map.of("userId", userId), UserFundRowMapper.watchlist());
    }

    @Transactional
    /**
     * 新增或更新一条自选基金记录，成功后尝试立即刷新估算快照。
     */
    public UserDtos.WatchlistItemResponse addWatchlist(Long userId, UserDtos.WatchlistRequest request) {
        assertUserExists(userId);
        ensureFundExistsForTrade(request.fundCode());
        jdbcTemplate.update("""
                INSERT INTO user_watchlist (user_id, fund_code, group_name, display_order, remark, alert_enabled)
                VALUES (:userId, :fundCode, :groupName, :displayOrder, :remark, :alertEnabled)
                ON DUPLICATE KEY UPDATE
                  group_name = VALUES(group_name),
                  display_order = VALUES(display_order),
                  remark = VALUES(remark),
                  alert_enabled = VALUES(alert_enabled),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("fundCode", request.fundCode())
                .addValue("groupName", trimToNull(request.groupName()))
                .addValue("displayOrder", request.displayOrder() == null ? 0 : request.displayOrder())
                .addValue("remark", trimToNull(request.remark()))
                .addValue("alertEnabled", Boolean.TRUE.equals(request.alertEnabled())));
        try {
            fundQuoteRefreshService.refreshFundEstimate(request.fundCode());
        } catch (Exception ignored) {
            // 行情源短暂超时不影响加入自选，后续调度会继续补齐估算数据。
        }
        return getWatchlistItem(userId, request.fundCode());
    }

    @Transactional
    public UserDtos.WatchlistItemResponse updateWatchlist(Long userId, Long watchId, UserDtos.WatchlistUpdateRequest request) {
        assertUserExists(userId);
        int updated = jdbcTemplate.update("""
                UPDATE user_watchlist
                SET group_name = COALESCE(:groupName, group_name),
                    display_order = COALESCE(:displayOrder, display_order),
                    remark = COALESCE(:remark, remark),
                    alert_enabled = COALESCE(:alertEnabled, alert_enabled)
                WHERE id = :watchId AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("watchId", watchId)
                .addValue("groupName", trimToNull(request.groupName()))
                .addValue("displayOrder", request.displayOrder())
                .addValue("remark", trimToNull(request.remark()))
                .addValue("alertEnabled", request.alertEnabled()));
        if (updated == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6570\u636e\u4e0d\u5b58\u5728");
        }
        return getWatchlistItemById(userId, watchId);
    }

    @Transactional
    public List<UserDtos.WatchlistItemResponse> reorderWatchlist(Long userId, UserDtos.WatchlistOrderRequest request) {
        assertUserExists(userId);
        if (request.items() == null || request.items().isEmpty()) {
            return listWatchlist(userId);
        }
        for (UserDtos.WatchlistOrderItem item : request.items()) {
            if (item.watchId() == null || item.displayOrder() == null) {
                continue;
            }
            jdbcTemplate.update("""
                    UPDATE user_watchlist
                    SET display_order = :displayOrder,
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :watchId AND user_id = :userId
                    """, new MapSqlParameterSource()
                    .addValue("userId", userId)
                    .addValue("watchId", item.watchId())
                    .addValue("displayOrder", Math.max(0, item.displayOrder())));
        }
        return listWatchlist(userId);
    }

    @Transactional
    public void deleteWatchlist(Long userId, Long watchId) {
        assertUserExists(userId);
        int deleted = jdbcTemplate.update("""
                DELETE FROM user_watchlist
                WHERE id = :watchId AND user_id = :userId
                """, Map.of("userId", userId, "watchId", watchId));
        if (deleted == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6570\u636e\u4e0d\u5b58\u5728");
        }
    }

    /**
     * 查询全部账户的持有明细。
     */
    public List<UserDtos.HoldingResponse> listHoldings(Long userId) {
        return listHoldings(userId, null, null, null, null);
    }

    /**
     * 查询持有明细，支持账户、基金筛选和前端指定排序。
     */
    public List<UserDtos.HoldingResponse> listHoldings(
            Long userId,
            String fundCode,
            Long accountId,
            String sortBy,
            String sortDirection
    ) {
        assertUserExists(userId);
        String fundFilter = StringUtils.hasText(fundCode) ? " AND h.fund_code = :fundCode " : " ";
        String accountFilter = accountId == null ? " " : " AND h.account_id = :accountId ";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId);
        if (StringUtils.hasText(fundCode)) {
            params.addValue("fundCode", fundCode.trim());
        }
        if (accountId != null) {
            params.addValue("accountId", accountId);
        }

        String sql = holdingListSql() + """
                WHERE h.user_id = :userId AND h.status <> 'DELETED'
                """ + fundFilter + accountFilter + """
                ORDER BY h.updated_at DESC, h.id DESC
                """;
        List<UserDtos.HoldingResponse> holdings = jdbcTemplate.query(sql, params, UserFundRowMapper.holding());
        holdings.addAll(listPendingHoldings(userId, fundCode, accountId));
        sortHoldingsInMemory(holdings, sortBy, sortDirection);
        return holdings;
    }

    /**
     * 查询账户级持有汇总，供前端账户筛选器展示。
     */
    public List<UserDtos.HoldingAccountSummaryResponse> listHoldingAccountSummary(Long userId) {
        assertUserExists(userId);
        return jdbcTemplate.query("""
                SELECT
                  acc.id AS account_id,
                  acc.account_name,
                  acc.platform_name,
                  SUM(CASE WHEN h.id IS NOT NULL AND h.status <> 'DELETED' THEN 1 ELSE 0 END) AS holding_count,
                  COALESCE(SUM(CASE WHEN h.id IS NOT NULL AND h.status <> 'DELETED' THEN h.cost_amount ELSE 0 END), 0) AS total_cost_amount,
                  COALESCE(SUM(
                    CASE
                      WHEN h.id IS NOT NULL AND h.status <> 'DELETED'
                      THEN h.holding_share * COALESCE(snap.estimate_nav, nav.unit_nav, 0)
                      ELSE 0
                    END
                  ), 0) AS total_market_value
                FROM user_holding_account acc
                LEFT JOIN user_fund_holding h ON h.account_id = acc.id
                LEFT JOIN fund_realtime_snapshot snap ON snap.fund_code = h.fund_code
                LEFT JOIN (
                  SELECT n1.fund_code, n1.unit_nav
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = h.fund_code
                WHERE acc.user_id = :userId
                GROUP BY acc.id, acc.account_name, acc.platform_name
                ORDER BY acc.id ASC
                """, Map.of("userId", userId), (rs, rowNum) -> {
            BigDecimal totalCostAmount = zeroIfNull(rs.getBigDecimal("total_cost_amount")).setScale(4, RoundingMode.HALF_UP);
            BigDecimal totalMarketValue = zeroIfNull(rs.getBigDecimal("total_market_value")).setScale(4, RoundingMode.HALF_UP);
            BigDecimal totalProfitLossAmount = totalMarketValue.subtract(totalCostAmount).setScale(4, RoundingMode.HALF_UP);
            BigDecimal totalProfitLossPct = ratioPct(totalProfitLossAmount, totalCostAmount);
            return new UserDtos.HoldingAccountSummaryResponse(
                    rs.getLong("account_id"),
                    rs.getString("account_name"),
                    rs.getString("platform_name"),
                    rs.getInt("holding_count"),
                    totalCostAmount,
                    totalMarketValue,
                    totalProfitLossAmount,
                    totalProfitLossPct
            );
        });
    }

    /**
     * 一次返回 summary、accounts、holdings 和 transactions，减少前端并发请求。
     */
    public UserDtos.HoldingDashboardResponse holdingDashboard(
            Long userId,
            Long accountId,
            String fundCode,
            Integer txnLimit,
            String sortBy,
            String sortDirection
    ) {
        List<UserDtos.HoldingResponse> holdings = listHoldings(userId, fundCode, accountId, sortBy, sortDirection);
        List<UserDtos.HoldingAccountSummaryResponse> accounts = listHoldingAccountSummary(userId);
        List<UserDtos.HoldingTransactionResponse> transactions = listHoldingTransactions(userId, fundCode, accountId, txnLimit);
        UserDtos.HoldingSummaryResponse summary = buildHoldingSummaryFromList(holdings);
        return new UserDtos.HoldingDashboardResponse(
                summary,
                accounts,
                holdings,
                transactions,
                LocalDateTime.now()
        );
    }

    @Transactional
    public UserDtos.HoldingResponse addHolding(Long userId, UserDtos.HoldingCreateRequest request) {
        assertUserExists(userId);
        ensureFundExistsForTrade(request.fundCode());
        Long accountId = findOrCreateAccount(userId, request.accountName(), request.platformName());
        jdbcTemplate.update("""
                INSERT INTO user_fund_holding (
                  user_id, account_id, fund_code, holding_share, frozen_share, cost_amount, avg_cost_nav, first_buy_date, status
                ) VALUES (
                  :userId, :accountId, :fundCode, :holdingShare, :frozenShare, :costAmount, :avgCostNav, :firstBuyDate, 'ACTIVE'
                )
                ON DUPLICATE KEY UPDATE
                  holding_share = VALUES(holding_share),
                  frozen_share = VALUES(frozen_share),
                  cost_amount = VALUES(cost_amount),
                  avg_cost_nav = VALUES(avg_cost_nav),
                  first_buy_date = VALUES(first_buy_date),
                  status = 'ACTIVE',
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", accountId)
                .addValue("fundCode", request.fundCode())
                .addValue("holdingShare", request.holdingShare())
                .addValue("frozenShare", request.frozenShare() == null ? BigDecimal.ZERO : request.frozenShare())
                .addValue("costAmount", request.costAmount())
                .addValue("avgCostNav", request.avgCostNav())
                .addValue("firstBuyDate", request.firstBuyDate()));
        return getHoldingByUserAccountFund(userId, accountId, request.fundCode());
    }

    @Transactional
    public UserDtos.HoldingResponse updateHolding(Long userId, Long holdingId, UserDtos.HoldingUpdateRequest request) {
        assertUserExists(userId);
        String status = nullableStatus(request.status());
        int updated = jdbcTemplate.update("""
                UPDATE user_fund_holding
                SET holding_share = COALESCE(:holdingShare, holding_share),
                    frozen_share = COALESCE(:frozenShare, frozen_share),
                    cost_amount = COALESCE(:costAmount, cost_amount),
                    avg_cost_nav = COALESCE(:avgCostNav, avg_cost_nav),
                    first_buy_date = COALESCE(:firstBuyDate, first_buy_date),
                    status = COALESCE(:status, status)
                WHERE id = :holdingId AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("holdingId", holdingId)
                .addValue("holdingShare", request.holdingShare())
                .addValue("frozenShare", request.frozenShare())
                .addValue("costAmount", request.costAmount())
                .addValue("avgCostNav", request.avgCostNav())
                .addValue("firstBuyDate", request.firstBuyDate())
                .addValue("status", status));
        if (updated == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6570\u636e\u4e0d\u5b58\u5728");
        }
        return getHolding(userId, holdingId);
    }

    @Transactional
    public void deleteHolding(Long userId, Long holdingId) {
        assertUserExists(userId);
        int updated = jdbcTemplate.update("""
                UPDATE user_fund_holding
                SET status = 'DELETED'
                WHERE id = :holdingId AND user_id = :userId
                """, Map.of("userId", userId, "holdingId", holdingId));
        if (updated == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6570\u636e\u4e0d\u5b58\u5728");
        }
    }

    /**
     * 持有板块汇总信息，仅按当前有效持仓计算实时市值与浮动盈亏。
     */
    public UserDtos.HoldingSummaryResponse holdingSummary(Long userId) {
        return buildHoldingSummaryFromList(listHoldings(userId));
    }

    /**
     * 查询模拟交易流水，支持按基金与账户过滤。
     */
    public List<UserDtos.HoldingTransactionResponse> listHoldingTransactions(Long userId, String fundCode, Long accountId, Integer limit) {
        assertUserExists(userId);
        int safeLimit = limit == null ? DEFAULT_TRADE_LIMIT : Math.max(1, Math.min(limit, MAX_TRADE_LIMIT));
        String fundFilter = StringUtils.hasText(fundCode) ? " AND t.fund_code = :fundCode " : " ";
        String accountFilter = accountId == null ? " " : " AND h.account_id = :accountId ";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("limit", safeLimit);
        if (StringUtils.hasText(fundCode)) {
            params.addValue("fundCode", fundCode.trim());
        }
        if (accountId != null) {
            params.addValue("accountId", accountId);
        }

        List<UserDtos.HoldingTransactionResponse> committed = jdbcTemplate.query("""
                SELECT
                  t.id,
                  t.holding_id,
                  h.account_id,
                  acc.account_name,
                  t.fund_code,
                  fsc.fund_name,
                  t.txn_type,
                  t.txn_date,
                  t.confirm_date,
                  t.amount,
                  t.share,
                  t.nav,
                  t.fee,
                  t.source_type,
                  t.remark,
                  t.created_at
                FROM user_holding_txn t
                LEFT JOIN user_fund_holding h ON h.id = t.holding_id
                LEFT JOIN user_holding_account acc ON acc.id = h.account_id
                JOIN fund_share_class fsc ON fsc.fund_code = t.fund_code
                WHERE t.user_id = :userId
                  %s
                  %s
                ORDER BY t.txn_date DESC, t.id DESC
                LIMIT :limit
                """.formatted(fundFilter, accountFilter), params, UserFundRowMapper.holdingTransaction());
        List<UserDtos.HoldingTransactionResponse> pending = listPendingTransactions(userId, fundCode, accountId);
        if (pending.isEmpty()) {
            return committed;
        }
        List<UserDtos.HoldingTransactionResponse> merged = new java.util.ArrayList<>(committed.size() + pending.size());
        merged.addAll(pending);
        merged.addAll(committed);
        merged.sort((left, right) -> {
            LocalDate leftDate = left.txnDate();
            LocalDate rightDate = right.txnDate();
            if (leftDate != null && rightDate != null) {
                int dateCompare = rightDate.compareTo(leftDate);
                if (dateCompare != 0) {
                    return dateCompare;
                }
            } else if (leftDate != null) {
                return -1;
            } else if (rightDate != null) {
                return 1;
            }
            LocalDateTime leftCreated = left.createdAt();
            LocalDateTime rightCreated = right.createdAt();
            if (leftCreated != null && rightCreated != null) {
                int createdCompare = rightCreated.compareTo(leftCreated);
                if (createdCompare != 0) {
                    return createdCompare;
                }
            } else if (leftCreated != null) {
                return -1;
            } else if (rightCreated != null) {
                return 1;
            }
            Long leftId = left.id();
            Long rightId = right.id();
            if (leftId == null && rightId == null) {
                return 0;
            }
            if (leftId == null) {
                return 1;
            }
            if (rightId == null) {
                return -1;
            }
            return Long.compare(rightId, leftId);
        });
        return merged.size() > safeLimit ? merged.subList(0, safeLimit) : merged;
    }

    /**
     * 把待成交队列映射为“交易记录”占位行，前端可直接展示“等待官方净值”。
     */
    private List<UserDtos.HoldingTransactionResponse> listPendingTransactions(Long userId, String fundCode, Long accountId) {
        String fundFilter = StringUtils.hasText(fundCode) ? " AND p.fund_code = :fundCode " : " ";
        String accountFilter = accountId == null ? " " : " AND p.account_id = :accountId ";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId);
        if (StringUtils.hasText(fundCode)) {
            params.addValue("fundCode", fundCode.trim());
        }
        if (accountId != null) {
            params.addValue("accountId", accountId);
        }
        return jdbcTemplate.query("""
                SELECT
                  p.id,
                  p.account_id,
                  acc.account_name,
                  p.fund_code,
                  fsc.fund_name,
                  p.txn_type,
                  p.txn_date,
                  p.target_nav_date,
                  p.amount,
                  p.share,
                  p.fee,
                  p.remark,
                  p.created_at
                FROM user_holding_pending_trade p
                LEFT JOIN user_holding_account acc ON acc.id = p.account_id
                JOIN fund_share_class fsc ON fsc.fund_code = p.fund_code
                WHERE p.user_id = :userId
                  AND p.status IN ('PENDING','PROCESSING')
                  %s
                  %s
                ORDER BY p.txn_date DESC, p.id DESC
                """.formatted(fundFilter, accountFilter), params, (rs, rowNum) -> new UserDtos.HoldingTransactionResponse(
                -Math.abs(rs.getLong("id")),
                null,
                rs.getObject("account_id", Long.class),
                rs.getString("account_name"),
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("txn_type"),
                rs.getDate("txn_date") == null ? null : rs.getDate("txn_date").toLocalDate(),
                rs.getDate("target_nav_date") == null ? null : rs.getDate("target_nav_date").toLocalDate(),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("share"),
                null,
                rs.getBigDecimal("fee"),
                "PENDING_OFFICIAL_NAV",
                StringUtils.hasText(rs.getString("remark"))
                        ? rs.getString("remark") + " [WAITING_OFFICIAL_NAV]"
                        : "WAITING_OFFICIAL_NAV",
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime()
        ));
    }

    /**
     * 把待成交队列映射为“持有明细”占位行。
     * 这些行只用于提示用户该基金在等待官方净值确认，不参与持仓数值计算。
     */
    private List<UserDtos.HoldingResponse> listPendingHoldings(Long userId, String fundCode, Long accountId) {
        String fundFilter = StringUtils.hasText(fundCode) ? " AND p.fund_code = :fundCode " : " ";
        String accountFilter = accountId == null ? " " : " AND p.account_id = :accountId ";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId);
        if (StringUtils.hasText(fundCode)) {
            params.addValue("fundCode", fundCode.trim());
        }
        if (accountId != null) {
            params.addValue("accountId", accountId);
        }
        return jdbcTemplate.query("""
                SELECT
                  p.id,
                  p.account_id,
                  acc.account_name,
                  acc.platform_name,
                  p.fund_code,
                  fsc.fund_name,
                  fp.fund_type,
                  p.txn_date,
                  p.created_at
                FROM user_holding_pending_trade p
                LEFT JOIN user_holding_account acc ON acc.id = p.account_id
                JOIN fund_share_class fsc ON fsc.fund_code = p.fund_code
                JOIN fund_product fp ON fp.id = fsc.product_id
                WHERE p.user_id = :userId
                  AND p.status IN ('PENDING','PROCESSING')
                  %s
                  %s
                ORDER BY p.created_at DESC, p.id DESC
                """.formatted(fundFilter, accountFilter), params, (rs, rowNum) -> new UserDtos.HoldingResponse(
                -Math.abs(rs.getLong("id")),
                rs.getObject("account_id", Long.class),
                rs.getString("account_name"),
                rs.getString("platform_name"),
                rs.getString("fund_code"),
                rs.getString("fund_name"),
                rs.getString("fund_type"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                rs.getDate("txn_date") == null ? null : rs.getDate("txn_date").toLocalDate(),
                null,
                null,
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                null,
                null,
                null,
                null,
                "PENDING",
                "PENDING"
        ));
    }

    /**
     * 模拟买入或卖出，成交后更新持仓并追加交易流水。
     */
    @Transactional
    public UserDtos.HoldingTradeResponse simulateTrade(Long userId, UserDtos.HoldingTradeRequest request) {
        assertUserExists(userId);
        ensureFundExistsForTrade(request.fundCode());

        String txnType = normalizeTradeTxnType(request.txnType());
        BigDecimal fee = nonNegative(request.fee(), "fee");
        LocalDate txnDate = request.txnDate() == null ? LocalDate.now(CN_MARKET_ZONE) : request.txnDate();
        LocalDate confirmDate = request.confirmDate() == null ? txnDate : request.confirmDate();
        Long accountId = resolveTradeAccountId(userId, request.accountId(), request.accountName(), request.platformName());
        String navOption = normalizeNavOption(request.navOption());

        // 兼容旧版本：如果前端仍然传了手工净值，优先按手工净值即时成交。
        if (request.nav() != null && request.nav().compareTo(BigDecimal.ZERO) > 0) {
            return executeTradeWithResolvedNav(
                    userId,
                    request.fundCode(),
                    txnType,
                    accountId,
                    request.amount(),
                    request.share(),
                    normalizeNav(request.nav()),
                    fee,
                    txnDate,
                    confirmDate,
                    trimToNull(request.remark()),
                    "USER_INPUT"
            );
        }

        if ("BUY".equals(txnType) && !StringUtils.hasText(request.navOption())) {
            LocalDate targetDate = resolveAutoBuyTargetNavDate(txnDate);
            String autoNavOption = targetDate.equals(txnDate) ? "TODAY" : "TOMORROW";
            OfficialNav officialNav = findOfficialNavOnOrAfter(request.fundCode(), targetDate);
            if (officialNav == null) {
                Long pendingId = createPendingTrade(
                        userId,
                        accountId,
                        request.fundCode(),
                        txnType,
                        txnDate,
                        confirmDate,
                        targetDate,
                        autoNavOption,
                        request.amount(),
                        request.share(),
                        fee,
                        trimToNull(request.remark())
                );
                return new UserDtos.HoldingTradeResponse(
                        null,
                        null,
                        null,
                        null,
                        TRADE_STATUS_PENDING,
                        "官方净值未更新，已进入待成交队列",
                        pendingId,
                        targetDate
                );
            }
            return executeTradeWithResolvedNav(
                    userId,
                    request.fundCode(),
                    txnType,
                    accountId,
                    request.amount(),
                    request.share(),
                    normalizeNav(officialNav.unitNav()),
                    fee,
                    txnDate,
                    officialNav.navDate(),
                    trimToNull(request.remark()),
                    "IMPORT"
            );
        }

        if ("TODAY".equals(navOption) || "TOMORROW".equals(navOption)) {
            LocalDate targetDate = "TODAY".equals(navOption) ? txnDate : txnDate.plusDays(1);
            OfficialNav officialNav = findOfficialNavOnOrAfter(request.fundCode(), targetDate);
            if (officialNav == null) {
                Long pendingId = createPendingTrade(
                        userId,
                        accountId,
                        request.fundCode(),
                        txnType,
                        txnDate,
                        confirmDate,
                        targetDate,
                        navOption,
                        request.amount(),
                        request.share(),
                        fee,
                        trimToNull(request.remark())
                );
                return new UserDtos.HoldingTradeResponse(
                        null,
                        null,
                        null,
                        null,
                        TRADE_STATUS_PENDING,
                        "官方净值未更新，已进入待成交队列",
                        pendingId,
                        targetDate
                );
            }
            return executeTradeWithResolvedNav(
                    userId,
                    request.fundCode(),
                    txnType,
                    accountId,
                    request.amount(),
                    request.share(),
                    normalizeNav(officialNav.unitNav()),
                    fee,
                    txnDate,
                    officialNav.navDate(),
                    trimToNull(request.remark()),
                    "IMPORT"
            );
        }

        if ("YESTERDAY".equals(navOption)) {
            LocalDate targetDate = txnDate.minusDays(1);
            OfficialNav officialNav = findOfficialNavOnOrBefore(request.fundCode(), targetDate);
            if (officialNav == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "昨天无可用官方净值，无法模拟成交");
            }
            return executeTradeWithResolvedNav(
                    userId,
                    request.fundCode(),
                    txnType,
                    accountId,
                    request.amount(),
                    request.share(),
                    normalizeNav(officialNav.unitNav()),
                    fee,
                    txnDate,
                    officialNav.navDate(),
                    trimToNull(request.remark()),
                    "IMPORT"
            );
        }

        BigDecimal autoNav = normalizeNav(resolveTradeNav(request.fundCode(), null));
        return executeTradeWithResolvedNav(
                userId,
                request.fundCode(),
                txnType,
                accountId,
                request.amount(),
                request.share(),
                autoNav,
                fee,
                txnDate,
                confirmDate,
                trimToNull(request.remark()),
                "USER_INPUT"
        );
    }

    /**
     * 定时任务入口：扫描待成交队列，拿到官方净值后自动落地为真实成交。
     *
     * @return 本轮成功落地的成交数量
     */
    public int settlePendingTrades() {
        List<PendingTrade> pendingTrades = jdbcTemplate.query("""
                SELECT
                  id, user_id, account_id, fund_code, txn_type, txn_date, confirm_date,
                  target_nav_date, nav_option, amount, share, fee, remark
                FROM user_holding_pending_trade
                WHERE status = 'PENDING'
                ORDER BY id ASC
                LIMIT 200
                """, (rs, rowNum) -> new PendingTrade(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("account_id"),
                rs.getString("fund_code"),
                rs.getString("txn_type"),
                rs.getDate("txn_date").toLocalDate(),
                rs.getDate("confirm_date") == null ? null : rs.getDate("confirm_date").toLocalDate(),
                rs.getDate("target_nav_date").toLocalDate(),
                rs.getString("nav_option"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("share"),
                rs.getBigDecimal("fee"),
                rs.getString("remark")
        ));
        int settledCount = 0;
        for (PendingTrade pendingTrade : pendingTrades) {
            settledCount += settleSinglePendingTrade(pendingTrade);
        }
        return settledCount;
    }

    /**
     * 把已经拿到官方净值的待成交记录落地成真实成交。
     */
    private int settleSinglePendingTrade(PendingTrade pendingTrade) {
        int locked = jdbcTemplate.update("""
                UPDATE user_holding_pending_trade
                SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = :id AND status = 'PENDING'
                """, Map.of("id", pendingTrade.id()));
        if (locked == 0) {
            return 0;
        }

        try {
            OfficialNav officialNav = findOfficialNavOnOrAfter(pendingTrade.fundCode(), pendingTrade.targetNavDate());
            if (officialNav == null) {
                jdbcTemplate.update("""
                        UPDATE user_holding_pending_trade
                        SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP(3)
                        WHERE id = :id AND status = 'PROCESSING'
                        """, Map.of("id", pendingTrade.id()));
                return 0;
            }

            UserDtos.HoldingTradeResponse response = executeTradeWithResolvedNav(
                    pendingTrade.userId(),
                    pendingTrade.fundCode(),
                    pendingTrade.txnType(),
                    pendingTrade.accountId(),
                    pendingTrade.amount(),
                    pendingTrade.share(),
                    normalizeNav(officialNav.unitNav()),
                    nonNegative(pendingTrade.fee(), "fee"),
                    pendingTrade.txnDate(),
                    officialNav.navDate(),
                    pendingTrade.remark(),
                    "IMPORT"
            );

            Long txnId = response.transaction() == null ? null : response.transaction().id();
            jdbcTemplate.update("""
                    UPDATE user_holding_pending_trade
                    SET status = 'DONE',
                        resolved_nav = :resolvedNav,
                        resolved_nav_date = :resolvedNavDate,
                        resolved_txn_id = :resolvedTxnId,
                        error_message = NULL,
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", pendingTrade.id())
                    .addValue("resolvedNav", officialNav.unitNav())
                    .addValue("resolvedNavDate", officialNav.navDate())
                    .addValue("resolvedTxnId", txnId));
            return 1;
        } catch (Exception exception) {
            jdbcTemplate.update("""
                    UPDATE user_holding_pending_trade
                    SET status = 'FAILED',
                        error_message = :errorMessage,
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :id
                    """, new MapSqlParameterSource()
                    .addValue("id", pendingTrade.id())
                    .addValue("errorMessage", trimTo255(exception.getMessage())));
            return 0;
        }
    }

    /**
     * 已经确定净值后，统一走这条路径落地成交，保证买卖、持仓和流水口径一致。
     */
    private UserDtos.HoldingTradeResponse executeTradeWithResolvedNav(
            Long userId,
            String fundCode,
            String txnType,
            Long accountId,
            BigDecimal requestAmount,
            BigDecimal requestShare,
            BigDecimal nav,
            BigDecimal fee,
            LocalDate txnDate,
            LocalDate confirmDate,
            String remark,
            String sourceType
    ) {
        LocalDate safeConfirmDate = confirmDate == null ? txnDate : confirmDate;
        HoldingSnapshot beforeHolding = findHoldingSnapshot(userId, accountId, fundCode);
        TradeCalculation calculation = calculateTrade(txnType, requestAmount, requestShare, nav, fee, beforeHolding);

        LocalDate firstBuyDate = beforeHolding.firstBuyDate();
        if ("BUY".equals(txnType)) {
            LocalDate candidate = safeConfirmDate;
            if (firstBuyDate == null || (candidate != null && candidate.isBefore(firstBuyDate))) {
                firstBuyDate = candidate;
            }
        }
        String nextStatus = calculation.nextShare().compareTo(BigDecimal.ZERO) > 0 ? "ACTIVE" : "CLEARED";

        Long holdingId = upsertHolding(
                userId,
                accountId,
                fundCode,
                beforeHolding.frozenShare(),
                calculation.nextShare(),
                calculation.nextCostAmount(),
                calculation.nextAvgCostNav(),
                firstBuyDate,
                nextStatus
        );

        Long txnId = insertHoldingTxn(
                holdingId,
                userId,
                fundCode,
                txnType,
                txnDate,
                safeConfirmDate,
                calculation.amount(),
                calculation.share(),
                nav,
                fee,
                remark,
                sourceType
        );

        UserDtos.HoldingResponse holding = getHolding(userId, holdingId);
        UserDtos.HoldingTransactionResponse transaction = getHoldingTransaction(userId, txnId);
        String statusText = "IMPORT".equalsIgnoreCase(sourceType) ? "已按官方净值成交" : "模拟成交已记录";
        return new UserDtos.HoldingTradeResponse(
                transaction,
                holding,
                calculation.realizedProfitLossAmount(),
                calculation.realizedProfitLossPct(),
                TRADE_STATUS_EXECUTED,
                statusText,
                null,
                safeConfirmDate
        );
    }

    private LocalDate resolveAutoBuyTargetNavDate(LocalDate txnDate) {
        LocalDate marketToday = LocalDate.now(CN_MARKET_ZONE);
        LocalDate safeTxnDate = txnDate == null ? marketToday : txnDate;
        if (!safeTxnDate.equals(marketToday)) {
            return nextTradingDay(safeTxnDate);
        }
        if (!isTradingDay(safeTxnDate)) {
            return nextTradingDay(safeTxnDate);
        }
        return isWithinCnTradeSessionOrLunchBreak() ? safeTxnDate : nextTradingDay(safeTxnDate);
    }

    private boolean isWithinCnTradeSessionOrLunchBreak() {
        LocalTime now = LocalTime.now(CN_MARKET_ZONE);
        return !now.isBefore(CN_MARKET_OPEN) && now.isBefore(CN_MARKET_CLOSE);
    }

    private boolean isTradingDay(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    private LocalDate nextTradingDay(LocalDate date) {
        LocalDate next = date;
        do {
            next = next.plusDays(1);
        } while (!isTradingDay(next));
        return next;
    }

    private String normalizeNavOption(String value) {
        if (!StringUtils.hasText(value)) {
            return "TODAY";
        }
        String normalized = value.trim().toUpperCase();
        if (!NAV_OPTION.contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "净值选项不合法");
        }
        return normalized;
    }

    /**
     * 创建待成交记录：用于“今天/明天按官方净值成交”场景。
     */
    private Long createPendingTrade(
            Long userId,
            Long accountId,
            String fundCode,
            String txnType,
            LocalDate txnDate,
            LocalDate confirmDate,
            LocalDate targetNavDate,
            String navOption,
            BigDecimal amount,
            BigDecimal share,
            BigDecimal fee,
            String remark
    ) {
        if ((amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                && (share == null || share.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请至少输入成交金额或成交份额");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO user_holding_pending_trade (
                  user_id, account_id, fund_code, txn_type, txn_date, confirm_date,
                  target_nav_date, nav_option, amount, share, fee, remark, status
                ) VALUES (
                  :userId, :accountId, :fundCode, :txnType, :txnDate, :confirmDate,
                  :targetNavDate, :navOption, :amount, :share, :fee, :remark, 'PENDING'
                )
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", accountId)
                .addValue("fundCode", fundCode)
                .addValue("txnType", txnType)
                .addValue("txnDate", txnDate)
                .addValue("confirmDate", confirmDate)
                .addValue("targetNavDate", targetNavDate)
                .addValue("navOption", navOption)
                .addValue("amount", amount)
                .addValue("share", share)
                .addValue("fee", fee == null ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP) : fee)
                .addValue("remark", remark), keyHolder);
        return extractGeneratedId(keyHolder, "待成交记录");
    }

    /**
     * 取某日期（含）之后第一条官方净值，用于今天/明天的“等官方净值成交”。
     */
    private OfficialNav findOfficialNavOnOrAfter(String fundCode, LocalDate targetDate) {
        FundNavCacheService.NavPoint navPoint = fundNavCacheService.loadOnOrAfter(fundCode, targetDate);
        if (navPoint == null || navPoint.unitNav() == null || navPoint.navDate() == null) {
            return null;
        }
        return new OfficialNav(navPoint.navDate(), navPoint.unitNav());
    }

    /**
     * 取某日期（含）之前最近一条官方净值，用于“昨天净值成交”。
     */
    private OfficialNav findOfficialNavOnOrBefore(String fundCode, LocalDate targetDate) {
        FundNavCacheService.NavPoint navPoint = fundNavCacheService.loadOnOrBefore(fundCode, targetDate);
        if (navPoint == null || navPoint.unitNav() == null || navPoint.navDate() == null) {
            return null;
        }
        return new OfficialNav(navPoint.navDate(), navPoint.unitNav());
    }

    private String trimTo255(String message) {
        if (!StringUtils.hasText(message)) {
            return "Unknown error";
        }
        String normalized = message.trim();
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private UserDtos.UserResponse getUserByOpenid(String openid) {
        return jdbcTemplate.queryForObject("""
                SELECT id, wechat_openid, wechat_unionid, nickname, avatar_url, status, last_login_at, created_at
                FROM app_user
                WHERE wechat_openid = :openid
                """, Map.of("openid", openid.trim()), UserFundRowMapper.user());
    }

    private UserDtos.WatchlistItemResponse getWatchlistItem(Long userId, String fundCode) {
        return jdbcTemplate.queryForObject("""
                SELECT
                  w.id,
                  w.fund_code,
                  fsc.fund_name,
                  fp.fund_type,
                  w.group_name,
                  w.display_order,
                  w.remark,
                  w.alert_enabled,
                  nav.nav_date,
                  nav.unit_nav,
                  nav.daily_return_pct,
                  snap.quote_time,
                  snap.estimate_nav,
                  snap.estimate_return_pct,
                  snap.data_status
                FROM user_watchlist w
                JOIN fund_share_class fsc ON fsc.fund_code = w.fund_code
                JOIN fund_product fp ON fp.id = fsc.product_id
                LEFT JOIN (
                  SELECT n1.fund_code, n1.nav_date, n1.unit_nav, n1.daily_return_pct
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    WHERE fund_code = :fundCode
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = w.fund_code
                LEFT JOIN fund_realtime_snapshot snap ON snap.fund_code = w.fund_code
                WHERE w.user_id = :userId AND w.fund_code = :fundCode
                """, Map.of("userId", userId, "fundCode", fundCode), UserFundRowMapper.watchlist());
    }

    private UserDtos.WatchlistItemResponse getWatchlistItemById(Long userId, Long watchId) {
        return jdbcTemplate.queryForObject("""
                SELECT
                  w.id,
                  w.fund_code,
                  fsc.fund_name,
                  fp.fund_type,
                  w.group_name,
                  w.display_order,
                  w.remark,
                  w.alert_enabled,
                  nav.nav_date,
                  nav.unit_nav,
                  nav.daily_return_pct,
                  snap.quote_time,
                  snap.estimate_nav,
                  snap.estimate_return_pct,
                  snap.data_status
                FROM user_watchlist w
                JOIN fund_share_class fsc ON fsc.fund_code = w.fund_code
                JOIN fund_product fp ON fp.id = fsc.product_id
                LEFT JOIN (
                  SELECT n1.fund_code, n1.nav_date, n1.unit_nav, n1.daily_return_pct
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = w.fund_code
                LEFT JOIN fund_realtime_snapshot snap ON snap.fund_code = w.fund_code
                WHERE w.user_id = :userId AND w.id = :watchId
                """, Map.of("userId", userId, "watchId", watchId), UserFundRowMapper.watchlist());
    }

    private UserDtos.HoldingResponse getHoldingByUserAccountFund(Long userId, Long accountId, String fundCode) {
        return jdbcTemplate.queryForObject(holdingListSql() + """
                WHERE h.user_id = :userId AND h.account_id = :accountId AND h.fund_code = :fundCode
                """, Map.of("userId", userId, "accountId", accountId, "fundCode", fundCode), UserFundRowMapper.holding());
    }

    private UserDtos.HoldingResponse getHolding(Long userId, Long holdingId) {
        try {
            return jdbcTemplate.queryForObject(holdingListSql() + """
                    WHERE h.user_id = :userId AND h.id = :holdingId
                    """, Map.of("userId", userId, "holdingId", holdingId), UserFundRowMapper.holding());
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6570\u636e\u4e0d\u5b58\u5728");
        }
    }

    private Long findOrCreateAccount(Long userId, String accountName, String platformName) {
        String name = StringUtils.hasText(accountName) ? accountName.trim() : DEFAULT_ACCOUNT_NAME;
        List<Long> ids = jdbcTemplate.query("""
                SELECT id
                FROM user_holding_account
                WHERE user_id = :userId AND account_name = :accountName
                LIMIT 1
                """, Map.of("userId", userId, "accountName", name), (rs, rowNum) -> rs.getLong("id"));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO user_holding_account (user_id, account_name, platform_name, is_default)
                VALUES (:userId, :accountName, :platformName, :isDefault)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountName", name)
                .addValue("platformName", trimToNull(platformName))
                .addValue("isDefault", DEFAULT_ACCOUNT_NAME.equals(name)), keyHolder);
        return extractGeneratedId(keyHolder, "持有账户");
    }

    private void assertUserExists(Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM app_user
                WHERE id = :userId AND status <> 'DELETED'
                """, Map.of("userId", userId), Integer.class);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u7528\u6237\u4e0d\u5b58\u5728");
        }
    }

    private void assertFundExists(String fundCode) {
        if (!fundExists(fundCode)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u57fa\u91d1\u4e0d\u5b58\u5728\u6216\u5df2\u7ec8\u6b62");
        }
    }

    /**
     * 持有与交易入口允许直接输入基金代码。本地不存在时先按代码从官方数据源导入，
     * 导入后仍不存在才返回 404，避免基金代码和名称错配。
     */
    private void ensureFundExistsForTrade(String fundCode) {
        String normalizedFundCode = trimToNull(fundCode);
        if (!StringUtils.hasText(normalizedFundCode)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u57fa\u91d1\u4ee3\u7801\u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (fundExists(normalizedFundCode)) {
            return;
        }
        try {
            officialFundImportService.searchAndImport(normalizedFundCode, 20);
        } catch (Exception ignored) {
            // ignored
        }
        if (!fundExists(normalizedFundCode)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u57fa\u91d1\u4e0d\u5b58\u5728\u6216\u5df2\u7ec8\u6b62");
        }
        try {
            fundQuoteRefreshService.refreshFundEstimate(normalizedFundCode);
        } catch (Exception ignored) {
            // ignored
        }
    }

    private boolean fundExists(String fundCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM fund_share_class
                WHERE fund_code = :fundCode AND status <> 'TERMINATED'
                """, Map.of("fundCode", fundCode), Integer.class);
        return count != null && count > 0;
    }

    private UserDtos.HoldingTransactionResponse getHoldingTransaction(Long userId, Long txnId) {
        try {
            return jdbcTemplate.queryForObject("""
                    SELECT
                      t.id,
                      t.holding_id,
                      h.account_id,
                      acc.account_name,
                      t.fund_code,
                      fsc.fund_name,
                      t.txn_type,
                      t.txn_date,
                      t.confirm_date,
                      t.amount,
                      t.share,
                      t.nav,
                      t.fee,
                      t.source_type,
                      t.remark,
                      t.created_at
                    FROM user_holding_txn t
                    LEFT JOIN user_fund_holding h ON h.id = t.holding_id
                    LEFT JOIN user_holding_account acc ON acc.id = h.account_id
                    JOIN fund_share_class fsc ON fsc.fund_code = t.fund_code
                    WHERE t.id = :txnId AND t.user_id = :userId
                    """, Map.of("txnId", txnId, "userId", userId), UserFundRowMapper.holdingTransaction());
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6570\u636e\u4e0d\u5b58\u5728");
        }
    }

    private Long resolveTradeAccountId(Long userId, Long accountId, String accountName, String platformName) {
        if (StringUtils.hasText(accountName)) {
            return findOrCreateAccount(userId, accountName, platformName);
        }
        if (accountId != null) {
            assertAccountBelongsUser(userId, accountId);
            return accountId;
        }
        return findOrCreateAccount(userId, null, platformName);
    }

    private void assertAccountBelongsUser(Long userId, Long accountId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM user_holding_account
                WHERE id = :accountId AND user_id = :userId
                """, Map.of("accountId", accountId, "userId", userId), Integer.class);
        if (count == null || count == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "\u6301\u6709\u8d26\u6237\u4e0d\u5b58\u5728");
        }
    }

    private BigDecimal resolveTradeNav(String fundCode, BigDecimal navFromRequest) {
        if (navFromRequest != null && navFromRequest.compareTo(BigDecimal.ZERO) > 0) {
            return navFromRequest;
        }
        // 自动模式优先使用实时估算；估算缺失时走 Redis->DB->官方回填的日净值链路。
        List<BigDecimal> estimateNav = jdbcTemplate.query("""
                SELECT snap.estimate_nav AS trade_nav
                FROM fund_realtime_snapshot snap
                WHERE snap.fund_code = :fundCode
                ORDER BY snap.quote_time DESC
                LIMIT 1
                """, Map.of("fundCode", fundCode), (rs, rowNum) -> rs.getBigDecimal("trade_nav"));
        if (!estimateNav.isEmpty() && estimateNav.get(0) != null && estimateNav.get(0).compareTo(BigDecimal.ZERO) > 0) {
            return estimateNav.get(0);
        }

        FundNavCacheService.NavPoint navPoint = fundNavCacheService.loadLatest(fundCode);
        if (navPoint == null || navPoint.unitNav() == null || navPoint.unitNav().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u6682\u65e0\u53ef\u7528\u51c0\u503c\uff0c\u65e0\u6cd5\u6a21\u62df\u6210\u4ea4");
        }
        return navPoint.unitNav();
    }

    private HoldingSnapshot findHoldingSnapshot(Long userId, Long accountId, String fundCode) {
        List<HoldingSnapshot> rows = jdbcTemplate.query("""
                SELECT id, holding_share, frozen_share, cost_amount, avg_cost_nav, first_buy_date, status
                FROM user_fund_holding
                WHERE user_id = :userId AND account_id = :accountId AND fund_code = :fundCode AND status <> 'DELETED'
                LIMIT 1
                """, Map.of("userId", userId, "accountId", accountId, "fundCode", fundCode), (rs, rowNum) -> new HoldingSnapshot(
                rs.getLong("id"),
                zeroIfNull(rs.getBigDecimal("holding_share")),
                zeroIfNull(rs.getBigDecimal("frozen_share")),
                zeroIfNull(rs.getBigDecimal("cost_amount")),
                rs.getBigDecimal("avg_cost_nav"),
                rs.getDate("first_buy_date") == null ? null : rs.getDate("first_buy_date").toLocalDate(),
                rs.getString("status")
        ));
        if (rows.isEmpty()) {
            return new HoldingSnapshot(null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, "CLEARED");
        }
        return rows.get(0);
    }

    private TradeCalculation calculateTrade(String txnType,
                                            BigDecimal requestAmount,
                                            BigDecimal requestShare,
                                            BigDecimal nav,
                                            BigDecimal fee,
                                            HoldingSnapshot beforeHolding) {
        BigDecimal amount = requestAmount;
        BigDecimal share = requestShare;
        if ((amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                && (share == null || share.compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u8bf7\u81f3\u5c11\u8f93\u5165\u6210\u4ea4\u91d1\u989d\u6216\u6210\u4ea4\u4efd\u989d");
        }

        if (share == null || share.compareTo(BigDecimal.ZERO) <= 0) {
            share = amount.divide(nav, 4, RoundingMode.HALF_UP);
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            amount = share.multiply(nav).setScale(4, RoundingMode.HALF_UP);
        } else {
            amount = amount.setScale(4, RoundingMode.HALF_UP);
        }

        if (share.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u6210\u4ea4\u4efd\u989d\u5fc5\u987b\u5927\u4e8e 0");
        }

        BigDecimal beforeShare = zeroIfNull(beforeHolding.holdingShare());
        BigDecimal beforeCost = zeroIfNull(beforeHolding.costAmount());
        BigDecimal realizedAmount = null;
        BigDecimal realizedPct = null;
        BigDecimal nextShare;
        BigDecimal nextCost;
        BigDecimal nextAvgCost;

        if ("BUY".equals(txnType)) {
            BigDecimal buyCost = amount.add(fee).setScale(4, RoundingMode.HALF_UP);
            nextShare = beforeShare.add(share).setScale(4, RoundingMode.HALF_UP);
            nextCost = beforeCost.add(buyCost).setScale(4, RoundingMode.HALF_UP);
            nextAvgCost = nextShare.compareTo(BigDecimal.ZERO) == 0
                    ? null
                    : nextCost.divide(nextShare, 6, RoundingMode.HALF_UP);
            return new TradeCalculation(
                    amount,
                    share,
                    nextShare,
                    nextCost,
                    nextAvgCost,
                    null,
                    null
            );
        }

        if (beforeHolding.id() == null || beforeShare.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u5f53\u524d\u6ca1\u6709\u53ef\u5356\u51fa\u7684\u6301\u4ed3");
        }
        BigDecimal sellableShare = beforeShare.subtract(zeroIfNull(beforeHolding.frozenShare()));
        if (share.compareTo(sellableShare) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u5356\u51fa\u4efd\u989d\u8d85\u8fc7\u53ef\u7528\u6301\u4ed3");
        }

        BigDecimal avgCost = beforeShare.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : beforeCost.divide(beforeShare, 10, RoundingMode.HALF_UP);
        BigDecimal reducedCost = avgCost.multiply(share).setScale(4, RoundingMode.HALF_UP);
        nextShare = beforeShare.subtract(share).setScale(4, RoundingMode.HALF_UP);
        nextCost = beforeCost.subtract(reducedCost).setScale(4, RoundingMode.HALF_UP);
        if (nextCost.compareTo(BigDecimal.ZERO) < 0) {
            nextCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (nextShare.compareTo(BigDecimal.ZERO) == 0) {
            nextCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            nextAvgCost = null;
        } else {
            nextAvgCost = nextCost.divide(nextShare, 6, RoundingMode.HALF_UP);
        }

        realizedAmount = amount.subtract(fee).subtract(reducedCost).setScale(4, RoundingMode.HALF_UP);
        realizedPct = ratioPct(realizedAmount, reducedCost);
        return new TradeCalculation(
                amount,
                share,
                nextShare,
                nextCost,
                nextAvgCost,
                realizedAmount,
                realizedPct
        );
    }

    private Long upsertHolding(Long userId,
                               Long accountId,
                               String fundCode,
                               BigDecimal frozenShare,
                               BigDecimal holdingShare,
                               BigDecimal costAmount,
                               BigDecimal avgCostNav,
                               LocalDate firstBuyDate,
                               String status) {
        jdbcTemplate.update("""
                INSERT INTO user_fund_holding (
                  user_id, account_id, fund_code, holding_share, frozen_share, cost_amount, avg_cost_nav, first_buy_date, status
                ) VALUES (
                  :userId, :accountId, :fundCode, :holdingShare, :frozenShare, :costAmount, :avgCostNav, :firstBuyDate, :status
                )
                ON DUPLICATE KEY UPDATE
                  holding_share = VALUES(holding_share),
                  frozen_share = VALUES(frozen_share),
                  cost_amount = VALUES(cost_amount),
                  avg_cost_nav = VALUES(avg_cost_nav),
                  first_buy_date = COALESCE(VALUES(first_buy_date), first_buy_date),
                  status = VALUES(status),
                  updated_at = CURRENT_TIMESTAMP(3)
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", accountId)
                .addValue("fundCode", fundCode)
                .addValue("holdingShare", holdingShare)
                .addValue("frozenShare", frozenShare)
                .addValue("costAmount", costAmount)
                .addValue("avgCostNav", avgCostNav)
                .addValue("firstBuyDate", firstBuyDate)
                .addValue("status", status));

        List<Long> ids = jdbcTemplate.query("""
                SELECT id
                FROM user_fund_holding
                WHERE user_id = :userId AND account_id = :accountId AND fund_code = :fundCode AND status <> 'DELETED'
                LIMIT 1
                """, Map.of("userId", userId, "accountId", accountId, "fundCode", fundCode), (rs, rowNum) -> rs.getLong("id"));
        if (ids.isEmpty()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "\u6301\u4ed3\u66f4\u65b0\u5931\u8d25");
        }
        return ids.get(0);
    }

    private Long insertHoldingTxn(Long holdingId,
                                  Long userId,
                                  String fundCode,
                                  String txnType,
                                  LocalDate txnDate,
                                  LocalDate confirmDate,
                                  BigDecimal amount,
                                  BigDecimal share,
                                  BigDecimal nav,
                                  BigDecimal fee,
                                  String remark,
                                  String sourceType) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update("""
                INSERT INTO user_holding_txn (
                  holding_id, user_id, fund_code, txn_type, txn_date, confirm_date, amount, share, nav, fee, source_type, remark
                ) VALUES (
                  :holdingId, :userId, :fundCode, :txnType, :txnDate, :confirmDate, :amount, :share, :nav, :fee, :sourceType, :remark
                )
                """, new MapSqlParameterSource()
                .addValue("holdingId", holdingId)
                .addValue("userId", userId)
                .addValue("fundCode", fundCode)
                .addValue("txnType", txnType)
                .addValue("txnDate", txnDate)
                .addValue("confirmDate", confirmDate)
                .addValue("amount", amount)
                .addValue("share", share)
                .addValue("nav", nav)
                .addValue("fee", fee)
                .addValue("sourceType", sourceType)
                .addValue("remark", remark), keyHolder);
        return extractGeneratedId(keyHolder, "交易流水");
    }

    private String normalizeTradeTxnType(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u4ea4\u6613\u7c7b\u578b\u4e0d\u80fd\u4e3a\u7a7a");
        }
        String normalized = value.trim().toUpperCase();
        if (!TRADE_TXN_TYPE.contains(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u4ec5\u652f\u6301 BUY \u6216 SELL");
        }
        return normalized;
    }

    private BigDecimal normalizeNav(BigDecimal nav) {
        if (nav == null || nav.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "\u6210\u4ea4\u51c0\u503c\u5fc5\u987b\u5927\u4e8e 0");
        }
        return nav.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 根据持有列表构建总览汇总，保证看板和独立汇总接口口径一致。
     */
    private UserDtos.HoldingSummaryResponse buildHoldingSummaryFromList(List<UserDtos.HoldingResponse> holdings) {
        BigDecimal totalCostAmount = BigDecimal.ZERO;
        BigDecimal totalMarketValue = BigDecimal.ZERO;
        int totalCount = 0;
        int activeCount = 0;
        LocalDateTime updatedAt = null;

        for (UserDtos.HoldingResponse holding : holdings) {
            if (!"DELETED".equalsIgnoreCase(holding.status())) {
                totalCount += 1;
            }
            if ("ACTIVE".equalsIgnoreCase(holding.status())) {
                activeCount += 1;
            }
            totalCostAmount = totalCostAmount.add(zeroIfNull(holding.costAmount()));
            totalMarketValue = totalMarketValue.add(zeroIfNull(holding.estimateMarketValue()));
            if (holding.estimateTime() != null && (updatedAt == null || holding.estimateTime().isAfter(updatedAt))) {
                updatedAt = holding.estimateTime();
            }
        }

        BigDecimal pnlAmount = totalMarketValue.subtract(totalCostAmount).setScale(4, RoundingMode.HALF_UP);
        return new UserDtos.HoldingSummaryResponse(
                totalCostAmount.setScale(4, RoundingMode.HALF_UP),
                totalMarketValue.setScale(4, RoundingMode.HALF_UP),
                pnlAmount,
                ratioPct(pnlAmount, totalCostAmount),
                totalCount,
                activeCount,
                updatedAt == null ? LocalDateTime.now() : updatedAt
        );
    }

    /**
     * 持有排序：默认按更新时间降序，也支持盈亏率、盈亏额、市值和成本排序。
     */
    private void sortHoldingsInMemory(List<UserDtos.HoldingResponse> holdings, String sortBy, String sortDirection) {
        if (holdings == null || holdings.isEmpty()) {
            return;
        }
        String normalizedSortBy = StringUtils.hasText(sortBy) ? sortBy.trim() : "updated";
        boolean asc = StringUtils.hasText(sortDirection) && "asc".equalsIgnoreCase(sortDirection.trim());

        Comparator<UserDtos.HoldingResponse> comparator;
        switch (normalizedSortBy) {
            case "pnlPct":
                comparator = (left, right) -> compareBigDecimal(left.estimateProfitLossPct(), right.estimateProfitLossPct());
                break;
            case "pnlAmount":
                comparator = (left, right) -> compareBigDecimal(left.estimateProfitLossAmount(), right.estimateProfitLossAmount());
                break;
            case "marketValue":
                comparator = (left, right) -> compareBigDecimal(left.estimateMarketValue(), right.estimateMarketValue());
                break;
            case "costAmount":
                comparator = (left, right) -> compareBigDecimal(left.costAmount(), right.costAmount());
                break;
            case "updated":
            default:
                comparator = (left, right) -> compareDateTime(left.estimateTime(), right.estimateTime());
                break;
        }

        if (!asc) {
            comparator = comparator.reversed();
        }
        holdings.sort(comparator.thenComparing(UserDtos.HoldingResponse::id, Comparator.nullsLast(Long::compareTo)));
    }

    private int compareBigDecimal(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private int compareDateTime(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, field + " \u4e0d\u80fd\u4e3a\u8d1f\u6570");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
                // fallback to throw business exception below
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, entityName + "创建失败");
    }

    private BigDecimal ratioPct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private String holdingListSql() {
        return """
                SELECT
                  h.id,
                  h.account_id,
                  acc.account_name,
                  acc.platform_name,
                  h.fund_code,
                  fsc.fund_name,
                  fp.fund_type,
                  h.holding_share,
                  h.frozen_share,
                  h.cost_amount,
                  h.avg_cost_nav,
                  NULL AS confirmed_amount,
                  NULL AS profit_loss_amount,
                  NULL AS profit_loss_pct,
                  h.first_buy_date,
                  h.status,
                  nav.nav_date,
                  nav.unit_nav,
                  snap.quote_time,
                  snap.estimate_nav,
                  snap.data_status
                FROM user_fund_holding h
                JOIN user_holding_account acc ON acc.id = h.account_id
                JOIN fund_share_class fsc ON fsc.fund_code = h.fund_code
                JOIN fund_product fp ON fp.id = fsc.product_id
                LEFT JOIN (
                  SELECT n1.fund_code, n1.nav_date, n1.unit_nav
                  FROM fund_nav_daily n1
                  JOIN (
                    SELECT fund_code, MAX(nav_date) AS nav_date
                    FROM fund_nav_daily
                    GROUP BY fund_code
                  ) n2 ON n2.fund_code = n1.fund_code AND n2.nav_date = n1.nav_date
                ) nav ON nav.fund_code = h.fund_code
                LEFT JOIN fund_realtime_snapshot snap ON snap.fund_code = h.fund_code
                """;
    }

    private String nullableStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!HOLDING_STATUS.contains(normalized)) {
            throw new BusinessException("\u6301\u6709\u72b6\u6001\u4e0d\u5408\u6cd5");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record HoldingSnapshot(
            Long id,
            BigDecimal holdingShare,
            BigDecimal frozenShare,
            BigDecimal costAmount,
            BigDecimal avgCostNav,
            LocalDate firstBuyDate,
            String status
    ) {
    }

    private record TradeCalculation(
            BigDecimal amount,
            BigDecimal share,
            BigDecimal nextShare,
            BigDecimal nextCostAmount,
            BigDecimal nextAvgCostNav,
            BigDecimal realizedProfitLossAmount,
            BigDecimal realizedProfitLossPct
    ) {
    }

    private record OfficialNav(
            LocalDate navDate,
            BigDecimal unitNav
    ) {
    }

    private record PendingTrade(
            Long id,
            Long userId,
            Long accountId,
            String fundCode,
            String txnType,
            LocalDate txnDate,
            LocalDate confirmDate,
            LocalDate targetNavDate,
            String navOption,
            BigDecimal amount,
            BigDecimal share,
            BigDecimal fee,
            String remark
    ) {
    }
}
