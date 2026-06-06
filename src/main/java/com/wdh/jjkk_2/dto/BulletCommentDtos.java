package com.wdh.jjkk_2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class BulletCommentDtos {
    public record SendRequest(
            @NotBlank(message = "弹幕内容不能为空")
            @Size(max = 120, message = "弹幕内容不能超过120个字符")
            String content,

            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "弹幕颜色格式不正确")
            String color
    ) {
    }

    public record ItemResponse(
            String id,
            String fundCode,
            String content,
            String color,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
    }

    public record ListResponse(
            String fundCode,
            int maxCount,
            int ttlSeconds,
            List<ItemResponse> items
    ) {
    }
}
