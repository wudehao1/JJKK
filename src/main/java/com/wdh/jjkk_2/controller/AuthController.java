package com.wdh.jjkk_2.controller;

import com.wdh.jjkk_2.dto.AuthDtos;
import com.wdh.jjkk_2.service.AuthService;

import com.wdh.jjkk_2.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序登录认证入口。
 *
 * Controller 只负责接收微信登录 code 并做基础参数校验，真正的微信 code 换
 * openid、用户资料创建或更新、后端 token 生成、Redis 会话保存都交给
 * {@link AuthService}。这样认证规则集中在 service，后续增加手机号登录或
 * token 刷新时不会把入口层写复杂。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 使用微信临时登录 code 换取后端 Bearer token。
     *
     * 前端第一次授权后把 code 发到这里，后端会返回用户信息和 token；
     * 后续访问用户自选、头像、反馈等接口时，都通过 Authorization 头携带该 token。
     */
    @PostMapping("/wechat-login")
    public ApiResponse<AuthDtos.LoginResponse> wechatLogin(@Valid @RequestBody AuthDtos.WechatLoginRequest request) {
        return ApiResponse.ok("\u767b\u5f55\u6210\u529f", authService.wechatLogin(request));
    }
}

