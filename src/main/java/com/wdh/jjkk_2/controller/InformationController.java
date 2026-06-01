package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.dto.InformationDtos;
import com.wdh.jjkk_2.service.InformationService;

import com.wdh.jjkk_2.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信息流接口。
 *
 * 资讯的上游刷新、重要信息识别、正文和图片等媒体提取、近三天保留策略都由
 * {@link InformationService} 负责。Controller 只暴露列表和详情读取接口，
 * 让小程序的信息板块可以懒加载、筛选重要信息并点开查看完整内容。
 */
@RestController
@RequestMapping("/information")
public class InformationController {
    private final InformationService informationService;

    public InformationController(InformationService informationService) {
        this.informationService = informationService;
    }

    /**
     * 分页返回资讯卡片列表，支持只看重要信息。
     *
     * 前端首次默认取 6 条，用户下滑时继续按页加载；列表只返回摘要、时间、
     * 重要标记和媒体概览，避免一次性传输完整正文。
     */
    @GetMapping
    public ApiResponse<InformationDtos.ListResponse> list(
            @RequestParam(defaultValue = "false") boolean importantOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "6") int size
    ) {
        return ApiResponse.ok(informationService.list(importantOnly, page, size));
    }

    /**
     * 返回单条资讯的完整内容和媒体元数据。
     *
     * 用户点击列表卡片后再请求详情，可以减少信息流首页的响应体大小，
     * 同时保留图片、图表等扩展内容的展示能力。
     */
    @GetMapping("/{id}")
    public ApiResponse<InformationDtos.DetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(informationService.detail(id));
    }
}

