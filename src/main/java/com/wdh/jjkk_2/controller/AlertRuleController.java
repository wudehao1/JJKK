package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.common.ApiResponse;
import com.wdh.jjkk_2.dto.AlertRuleDtos;
import com.wdh.jjkk_2.service.AlertRuleService;
import com.wdh.jjkk_2.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class AlertRuleController {
    private final AlertRuleService alertRuleService;
    private final AuthService authService;

    public AlertRuleController(AlertRuleService alertRuleService, AuthService authService) {
        this.alertRuleService = alertRuleService;
        this.authService = authService;
    }

    @GetMapping("/{userId}/fund-alerts/{fundCode}")
    public ApiResponse<AlertRuleDtos.AlertSettingResponse> getSettings(
            @PathVariable Long userId,
            @PathVariable String fundCode,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok(alertRuleService.getSettings(userId, fundCode));
    }

    @PutMapping("/{userId}/fund-alerts/{fundCode}")
    public ApiResponse<AlertRuleDtos.AlertSettingResponse> saveSettings(
            @PathVariable Long userId,
            @PathVariable String fundCode,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody AlertRuleDtos.AlertSettingRequest request
    ) {
        authService.requireUser(authorization, userId);
        return ApiResponse.ok("提醒设置已保存", alertRuleService.saveSettings(userId, fundCode, request));
    }
}
