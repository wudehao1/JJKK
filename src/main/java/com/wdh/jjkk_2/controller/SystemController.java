package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.common.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 轻量级服务发现和健康检查接口。
 *
 * 这两个接口不需要登录，主要用于浏览器直接访问、微信开发者工具调试、
 * 部署后健康探针检查。index 会列出当前后端暴露的主要接口，health 只返回
 * 服务是否可用和当前时间，适合负载均衡或运维脚本快速探活。
 */
@RestController
public class SystemController {
    @GetMapping({"", "/"})
    public ApiResponse<Map<String, Object>> index() {
        return ApiResponse.ok(Map.of(
                "service", "JJKK_2",
                "status", "UP",
                "time", LocalDateTime.now().toString(),
                "endpoints", List.of(
                        "GET /api/health",
                        "GET /api/market/overview",
                        "GET /api/market/fund-breadth",
                        "GET /api/market/fund-rankings",
                        "GET /api/market/sector-rankings",
                        "GET /api/market/indices/{symbol}/minute",
                        "GET /api/market/indices/{symbol}/history",
                        "GET /api/funds",
                        "GET /api/funds/official/search",
                        "GET /api/funds/{fundCode}/minute",
                        "GET /api/funds/{fundCode}/history",
                        "POST /api/auth/wechat-login",
                        "GET /api/information",
                        "GET /api/information/{id}",
                        "POST /api/feedback",
                        "GET /api/users/{userId}",
                        "PUT /api/users/{userId}/profile",
                        "POST /api/users/{userId}/avatar",
                        "GET /api/users/{userId}/watchlist",
                        "GET /api/users/{userId}/holdings",
                        "GET /api/users/{userId}/holdings/summary",
                        "GET /api/users/{userId}/holdings/transactions",
                        "GET /api/users/{userId}/holdings/dashboard",
                        "POST /api/users/{userId}/holdings/trades",
                        "WS /api/ws/funds"
                )
        ));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", "JJKK_2",
                "status", "UP",
                "time", LocalDateTime.now().toString()
        ));
    }
}

