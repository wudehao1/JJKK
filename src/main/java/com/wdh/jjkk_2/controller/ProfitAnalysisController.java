package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.common.ApiResponse;
import com.wdh.jjkk_2.dto.ProfitAnalysisDtos;
import com.wdh.jjkk_2.service.AuthService;
import com.wdh.jjkk_2.service.ProfitAnalysisService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class ProfitAnalysisController {
    private final ProfitAnalysisService profitAnalysisService;
    private final AuthService authService;

    public ProfitAnalysisController(ProfitAnalysisService profitAnalysisService, AuthService authService) {
        this.profitAnalysisService = profitAnalysisService;
        this.authService = authService;
    }

    @GetMapping("/{userId}/profit-analysis")
    public ApiResponse<ProfitAnalysisDtos.AnalysisResponse> analyze(
            @PathVariable Long userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "week") String range,
            @RequestParam(defaultValue = "day") String calendarMode,
            @RequestParam(required = false) String anchor,
            @RequestParam(required = false) String detailKey
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(profitAnalysisService.analyze(
                userId,
                accountId,
                range,
                calendarMode,
                anchor,
                detailKey
        ));
    }
}
