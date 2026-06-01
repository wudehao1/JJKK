package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.dto.FundDtos;
import com.wdh.jjkk_2.dto.OfficialFundDtos;
import com.wdh.jjkk_2.service.FundService;
import com.wdh.jjkk_2.service.OfficialFundImportService;

import com.wdh.jjkk_2.common.ApiResponse;
import com.wdh.jjkk_2.common.BusinessException;
import com.wdh.jjkk_2.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

/**
 * 基金查询、官方搜索导入和基金走势图接口。
 *
 * 普通用户不能手动新增、修改、删除基金基础信息。基金必须通过官方搜索接口拉取
 * 公开数据后再写入本地库，避免用户手输造成基金名称、代码、类型等基础数据失真。
 * Controller 保持接口编排，数据同步、估算、缓存读取等复杂逻辑放在 service。
 */
@Validated
@RestController
@RequestMapping("/funds")
public class FundController {
    private final FundService fundService;
    private final OfficialFundImportService officialFundImportService;

    public FundController(FundService fundService, OfficialFundImportService officialFundImportService) {
        this.fundService = fundService;
        this.officialFundImportService = officialFundImportService;
    }

    /**
     * 查询本地已标准化的基金索引。
     *
     * 这个接口只查 MySQL 中已经导入过的基金，适合前端做本地列表和分页展示；
     * 如果用户搜索一个本地没有的基金，应调用 official/search 先从公开源同步。
     */
    @GetMapping
    public ApiResponse<PageResponse<FundDtos.SummaryResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(fundService.search(keyword, page, size));
    }

    /**
     * 查询公开基金索引，并把搜索结果中的基金基础信息写入本地库。
     *
     * 用户在自选弹窗里搜索基金时走这个接口。后端会尽量从公开源拿真实基金代码、
     * 名称和拼音等信息，随后本地自选、详情、估算接口都基于这些标准化数据工作。
     */
    @GetMapping("/official/search")
    public ApiResponse<OfficialFundDtos.SearchResponse> searchOfficial(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok("\u5b98\u65b9\u6570\u636e\u5df2\u540c\u6b65", officialFundImportService.searchAndImport(keyword, limit));
    }

    @GetMapping("/{fundCode}")
    public ApiResponse<FundDtos.DetailResponse> getByCode(
            @PathVariable @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode
    ) {
        return ApiResponse.ok(fundService.getByCode(fundCode));
    }

    /**
     * 返回基金当日分时估算线。
     *
     * service 会按“先刷新最新估值、再读 Redis 当日分钟缓存、再读 MySQL 历史分时、
     * 最后用最新快照兜底”的顺序组织数据。前端只需要拿 points 画折线，不必关心
     * 数据当前来自实时估算、官方收盘修正还是历史缓存。
     */
    @GetMapping("/{fundCode}/minute")
    public ApiResponse<FundDtos.MinuteSeriesResponse> minuteSeries(
            @PathVariable @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode
    ) {
        return ApiResponse.ok(fundService.minuteSeries(fundCode));
    }

    /**
     * 返回基金近 1 月、3 月、6 月、1 年等区间的日净值走势。
     *
     * 这类图按交易日转折，不按分钟转折，数据优先来自基金公司/公开源披露的
     * 官方净值记录，适合用于基金详情页的长期走势。
     */
    @GetMapping("/{fundCode}/history")
    public ApiResponse<FundDtos.HistorySeriesResponse> historySeries(
            @PathVariable @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode,
            @RequestParam(defaultValue = "1m") String range
    ) {
        return ApiResponse.ok(fundService.historySeries(fundCode, range));
    }

    @PutMapping("/{fundCode}")
    public ApiResponse<FundDtos.DetailResponse> update(
            @PathVariable @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode,
            @Valid @RequestBody FundDtos.UpdateRequest request
    ) {
        throw new BusinessException(HttpStatus.FORBIDDEN, "\u4e0d\u5141\u8bb8\u624b\u52a8\u7ef4\u62a4\u57fa\u91d1\u4fe1\u606f");
    }

    @DeleteMapping("/{fundCode}")
    public ApiResponse<Void> deactivate(
            @PathVariable @Pattern(regexp = "\\d{6}", message = "\u57fa\u91d1\u4ee3\u7801\u5fc5\u987b\u662f6\u4f4d\u6570\u5b57") String fundCode
    ) {
        throw new BusinessException(HttpStatus.FORBIDDEN, "\u4e0d\u5141\u8bb8\u624b\u52a8\u7ef4\u62a4\u57fa\u91d1\u4fe1\u606f");
    }
}
