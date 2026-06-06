package com.wdh.jjkk_2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class AlertRuleDtos {
    private AlertRuleDtos() {
    }

    public record RuleRequest(
            @NotBlank
            @Pattern(regexp = "DAILY_RISE|DAILY_FALL|NAV_RISE|NAV_FALL")
            String ruleKey,
            Boolean enabled,
            BigDecimal threshold
    ) {
    }

    public record AlertSettingRequest(
            @NotBlank
            @Pattern(regexp = "IMMEDIATE|AT_1430|CUSTOM")
            String remindMode,
            @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$")
            String customTime,
            @NotNull List<@Valid RuleRequest> rules
    ) {
    }

    public record RuleResponse(
            String ruleKey,
            Boolean enabled,
            BigDecimal threshold
    ) {
    }

    public record AlertSettingResponse(
            String fundCode,
            String remindMode,
            String customTime,
            List<RuleResponse> rules,
            LocalDateTime updatedAt
    ) {
    }
}
