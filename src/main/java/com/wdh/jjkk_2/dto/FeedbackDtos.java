package com.wdh.jjkk_2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 建议反馈接口使用的请求和响应对象。
 *
 * 请求中保留分类、正文、联系方式和设备信息，方便后续人工排查问题；响应只返回提交时间，
 * 因为反馈当前采用日志落盘，不需要向前端暴露内部文件路径或日志编号。
 */
public final class FeedbackDtos {
    private FeedbackDtos() {
    }

    public record FeedbackRequest(
            @Size(max = 32) String category,
            @NotBlank @Size(max = 2000) String content,
            @Size(max = 128) String contact,
            @Size(max = 512) String deviceInfo
    ) {
    }

    public record FeedbackResponse(
            LocalDateTime submittedAt
    ) {
    }
}

