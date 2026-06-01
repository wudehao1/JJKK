package com.wdh.jjkk_2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 登录认证相关的数据传输对象。
 *
 * 小程序只把微信登录 code、授权得到的昵称和头像传到后端；后端返回自己的 token、
 * 数据库用户 id、9 位展示 id 和 token 过期时间。微信 openid 不直接暴露在登录响应中，
 * 避免前端依赖第三方身份字段。
 */
public final class AuthDtos {
    private AuthDtos() {
    }

    public record WechatLoginRequest(
            @NotBlank String code,
            @Size(max = 128) String nickname,
            @Size(max = 512) String avatarUrl
    ) {
    }

    public record LoginResponse(
            String token,
            Long userId,
            String displayUserId,
            String nickname,
            LocalDateTime expiresAt
    ) {
    }
}

