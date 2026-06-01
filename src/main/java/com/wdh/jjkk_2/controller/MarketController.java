package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.dto.MarketDtos;
import com.wdh.jjkk_2.service.MarketService;

import com.wdh.jjkk_2.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大盘首页、市场广度、排行榜、分时线和历史线接口。
 *
 * 第三方行情源经常存在慢、断、字段不一致的问题，所以源选择和兜底逻辑都封装在
 * {@link MarketService}。Controller 对前端保持稳定接口：小程序只需要传 symbol
 * 和 range，不需要知道实际数据来自东方财富、腾讯、新浪、Yahoo 还是本地缓存。
 */
@RestController
@RequestMapping("/market")
public class MarketController {
    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    /**
     * 按首页展示顺序返回已配置的大盘/指数/商品行情。
     *
     * 返回结果会尽量包含最新价、涨跌幅、更新时间和数据状态；如果某个外部源暂时
     * 不可用，也会返回占位数据，保证首页布局不会因为单个市场缺失而错乱。
     */
    @GetMapping("/overview")
    public ApiResponse<MarketDtos.OverviewResponse> overview() {
        return ApiResponse.ok(marketService.overview());
    }

    @GetMapping("/fund-breadth")
    public ApiResponse<MarketDtos.FundBreadthResponse> fundBreadth() {
        return ApiResponse.ok(marketService.fundBreadth());
    }

    @GetMapping("/fund-rankings")
    public ApiResponse<List<MarketDtos.FundRankingResponse>> fundRankings(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(marketService.fundRankings(limit));
    }

    @GetMapping("/sector-rankings")
    public ApiResponse<List<MarketDtos.SectorRankingResponse>> sectorRankings(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(marketService.sectorRankings(limit));
    }

    /**
     * 返回某个大盘品种的当日分时线。
     *
     * 交易时段内优先刷新实时源，非交易时段展示最近一个有数据的交易日，
     * 避免用户晚上或周末打开详情页时看到空白图。
     */
    @GetMapping("/indices/{symbol}/minute")
    public ApiResponse<MarketDtos.MinuteSeriesResponse> minuteSeries(@PathVariable String symbol) {
        return ApiResponse.ok(marketService.minuteSeries(symbol));
    }

    /**
     * 返回某个大盘品种的日 K 历史走势。
     *
     * 近 1 月、3 月、6 月、1 年等区间按交易日生成点位，供前端用同一套折线图组件
     * 展示长期趋势。
     */
    @GetMapping("/indices/{symbol}/history")
    public ApiResponse<MarketDtos.HistorySeriesResponse> historySeries(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String range
    ) {
        return ApiResponse.ok(marketService.historySeries(symbol, range));
    }
}

