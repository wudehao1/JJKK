package com.wdh.jjkk_2.realtime;

import com.wdh.jjkk_2.service.IntradayRedisCacheService;

import com.wdh.jjkk_2.service.FundQuoteRefreshService;
import com.wdh.jjkk_2.service.FundNavCacheService;
import com.wdh.jjkk_2.service.InformationService;
import com.wdh.jjkk_2.service.MarketService;
import com.wdh.jjkk_2.service.UserFundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 行情刷新、收盘校准、Redis 落库和资讯清理的统一调度器。
 *
 * 所有 cron 表达式集中在这里，方便评估 2 核 2G 服务器在每分钟、半小时、午夜等时间点
 * 的负载。真正的业务执行仍放在各 service 中，调度器只负责按时间触发和吞掉单次失败，
 * 防止一个外部源异常导致整个定时线程退出。
 */
@Component
public class MinuteDataScheduler {
    private static final Logger log = LoggerFactory.getLogger(MinuteDataScheduler.class);
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId US_EASTERN_ZONE = ZoneId.of("America/New_York");

    private final MarketService marketService;
    private final FundQuoteRefreshService fundQuoteRefreshService;
    private final FundNavCacheService fundNavCacheService;
    private final IntradayRedisCacheService intradayRedisCacheService;
    private final InformationService informationService;
    private final UserFundService userFundService;

    public MinuteDataScheduler(
            MarketService marketService,
            FundQuoteRefreshService fundQuoteRefreshService,
            FundNavCacheService fundNavCacheService,
            IntradayRedisCacheService intradayRedisCacheService,
            InformationService informationService,
            UserFundService userFundService
    ) {
        this.marketService = marketService;
        this.fundQuoteRefreshService = fundQuoteRefreshService;
        this.fundNavCacheService = fundNavCacheService;
        this.intradayRedisCacheService = intradayRedisCacheService;
        this.informationService = informationService;
        this.userFundService = userFundService;
    }

    /**
     * 交易时段按分钟刷新大盘分时和活跃基金估算。
     *
     * 大盘数据以抓取公开源为主，基金数据以自选/最近浏览的活跃集合为主，避免小服务器
     * 在一分钟内计算全市场基金。单只基金或单个市场刷新失败时只记录并跳过，下一分钟继续。
     */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void refreshMinuteData() {
        if (isMarketTradingMinute()) {
            try {
                marketService.refreshDefaultIndexIntraday();
            } catch (Exception exception) {
                log.warn("Minute market refresh failed", exception);
            }
        }
        if (isFundTradingMinute()) {
            try {
                fundQuoteRefreshService.refreshActiveFundEstimates();
            } catch (Exception exception) {
                log.warn("Minute fund estimate refresh failed", exception);
            }
        }
    }

    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Shanghai")
    public void clearTodayMinuteData() {
        try {
            fundQuoteRefreshService.clearTodayFundMinutes();
            marketService.clearTodayMinuteQuotes();
        } catch (Exception exception) {
            log.warn("Today minute quote cleanup failed", exception);
        }
    }

    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = "Asia/Shanghai")
    public void clearUsPreOpenMinuteData() {
        try {
            marketService.clearUsPreOpenMinuteData();
        } catch (Exception exception) {
            log.warn("US pre-open minute quote cleanup failed", exception);
        }
    }

    @Scheduled(cron = "0 0,30 19-23 * * MON-FRI", zone = "Asia/Shanghai")
    public void reconcileClosedFundMinutes() {
        try {
            fundQuoteRefreshService.reconcileActiveFundClosedMinutes();
        } catch (Exception exception) {
            log.warn("Closed fund minute reconciliation failed", exception);
        }
    }

    @Scheduled(cron = "0 5 0 * * TUE-SAT", zone = "Asia/Shanghai")
    public void reconcileLateClosedFundMinutes() {
        try {
            fundQuoteRefreshService.reconcileActiveFundClosedMinutes();
        } catch (Exception exception) {
            log.warn("Late closed fund minute reconciliation failed", exception);
        }
    }

    /**
     * 每 5 分钟扫描一次待成交队列：
     * 对于“今天/明天按官方净值成交”的请求，拿到官方净值后自动落地成交。
     */
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Shanghai")
    public void settlePendingHoldingTrades() {
        try {
            userFundService.settlePendingTrades();
        } catch (Exception exception) {
            log.warn("Pending holding trade settlement failed", exception);
        }
    }

    /**
     * 每天午夜把上一交易日 Redis 分时缓存复制到 MySQL。
     *
     * 白天用 Redis 保证读写速度，夜间再统一持久化，既减轻数据库高峰压力，
     * 也能为第二天或非交易时段展示上一日分时图提供历史数据。
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Shanghai")
    public void persistRedisIntradayData() {
        LocalDate tradingDay = LocalDate.now(CHINA_ZONE).minusDays(1);
        try {
            intradayRedisCacheService.persistCachedIntraday(tradingDay);
        } catch (Exception exception) {
            log.warn("Redis intraday persistence failed", exception);
        }
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Shanghai")
    public void refreshInformation() {
        try {
            informationService.refreshLatest();
        } catch (Exception exception) {
            log.warn("Information refresh failed", exception);
        }
    }

    /**
     * 全市场基金日净值增量补齐。
     *
     * 按批次推进全部基金（约 1.8 万只），每次只抓一小批，避免 2 核 2G 机器被全量同步打满。
     * 读取链路仍保持 Redis -> DB -> 官方接口，批量任务只是提前把库补齐，减少用户查询 miss。
     */
    @Scheduled(cron = "0 12,42 * * * *", zone = "Asia/Shanghai")
    public void syncAllFundDailyNavBatch() {
        try {
            fundNavCacheService.syncAllFundsNavBatch(420);
        } catch (Exception exception) {
            log.warn("All fund daily nav batch sync failed", exception);
        }
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Shanghai")
    public void cleanupInformation() {
        try {
            informationService.cleanupOld();
        } catch (Exception exception) {
            log.warn("Information cleanup failed", exception);
        }
    }

    private boolean isFundTradingMinute() {
        LocalTime time = LocalTime.now(CHINA_ZONE);
        return (!time.isBefore(LocalTime.of(9, 30)) && !time.isAfter(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 0)) && !time.isAfter(LocalTime.of(15, 0)));
    }

    private boolean isMarketTradingMinute() {
        LocalDate today = LocalDate.now(CHINA_ZONE);
        LocalTime time = LocalTime.now(CHINA_ZONE);
        DayOfWeek dayOfWeek = today.getDayOfWeek();
        boolean weekday = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;

        boolean chinaOrHongKongSession = weekday
                && !time.isBefore(LocalTime.of(9, 30))
                && !time.isAfter(LocalTime.of(16, 10));

        LocalDate usToday = LocalDate.now(US_EASTERN_ZONE);
        LocalTime usTime = LocalTime.now(US_EASTERN_ZONE);
        DayOfWeek usDayOfWeek = usToday.getDayOfWeek();
        boolean usWeekday = usDayOfWeek != DayOfWeek.SATURDAY && usDayOfWeek != DayOfWeek.SUNDAY;
        boolean usSession = usWeekday
                && !usTime.isBefore(LocalTime.of(9, 25))
                && !usTime.isAfter(LocalTime.of(16, 10));

        boolean goldDaySession = weekday
                && ((!time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 30)) && !time.isAfter(LocalTime.of(15, 30))));
        boolean goldNightSession = ((dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY) && !time.isBefore(LocalTime.of(20, 45)))
                || (dayOfWeek != DayOfWeek.SUNDAY && dayOfWeek != DayOfWeek.MONDAY && !time.isAfter(LocalTime.of(2, 30)));

        return chinaOrHongKongSession || usSession || goldDaySession || goldNightSession;
    }
}

