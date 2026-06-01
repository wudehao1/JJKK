package com.wdh.jjkk_2.dto;

import java.time.LocalDateTime;

/**
 * Redis 中保存的后端登录会话。
 *
 * token 本身只是随机字符串，真正的用户身份、微信 openid 和过期时间保存在这个对象里。
 * 用户私有接口会从 Redis 读出 AuthSession，再与 URL 中的 userId 做一致性校验。
 */
public record AuthSession(
        Long userId,
        String openid,
        LocalDateTime expiresAt
) {
}

