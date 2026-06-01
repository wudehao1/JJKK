package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.dto.FeedbackDtos;
import com.wdh.jjkk_2.service.FeedbackService;

import com.wdh.jjkk_2.service.AuthService;
import com.wdh.jjkk_2.dto.AuthSession;
import com.wdh.jjkk_2.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 建议与问题反馈接口。
 *
 * 反馈属于运营辅助数据，不参与基金行情和用户资产计算，所以由
 * {@link FeedbackService} 追加写入每日日志文件。这样实现成本低、对主库压力小，
 * 也能避免测试阶段大量反馈内容污染核心基金表。
 */
@RestController
@RequestMapping("/feedback")
public class FeedbackController {
    private final FeedbackService feedbackService;
    private final AuthService authService;

    public FeedbackController(FeedbackService feedbackService, AuthService authService) {
        this.feedbackService = feedbackService;
        this.authService = authService;
    }

    /**
     * 保存当前登录用户提交的一条反馈。
     *
     * 这里先校验 token，拿到真实 userId，再把反馈内容交给 service 写日志，
     * 防止前端伪造用户身份提交反馈。
     */
    @PostMapping
    public ApiResponse<FeedbackDtos.FeedbackResponse> submit(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody FeedbackDtos.FeedbackRequest request
    ) {
        AuthSession session = authService.requireSession(authorization);
        return ApiResponse.ok("\u53cd\u9988\u5df2\u63d0\u4ea4", feedbackService.submit(session.userId(), request));
    }
}
