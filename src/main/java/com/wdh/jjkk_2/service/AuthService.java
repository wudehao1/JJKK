package com.wdh.jjkk_2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wdh.jjkk_2.common.BusinessException;
import com.wdh.jjkk_2.dto.AuthDtos;
import com.wdh.jjkk_2.dto.AuthSession;
import com.wdh.jjkk_2.dto.UserDtos;
import com.wdh.jjkk_2.service.UserFundService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 微信登录和后端 token 会话服务。
 *
 * 后端不会直接相信前端传来的 userId。用户登录成功后，服务端生成不可猜测的
 * Bearer token，并把 token 对应的用户会话保存到 Redis；所有用户私有接口都必须
 * 调用 {@link #requireUser(String, Long)} 校验 token 与路径 userId 是否一致。
 * 这能保证测试用户之间的数据隔离，避免通过篡改 URL 访问他人自选或资料。
 */
@Service
public class AuthService {
    private static final String TOKEN_PREFIX = "jjkk:auth:token:";
    private static final String WECHAT_SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserFundService userFundService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String wechatAppId;
    private final String wechatAppSecret;
    private final boolean allowDevLogin;
    private final Duration tokenTtl;

    public AuthService(
            UserFundService userFundService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            @Value("${jjkk.wechat.app-id:}") String wechatAppId,
            @Value("${jjkk.wechat.app-secret:}") String wechatAppSecret,
            @Value("${jjkk.auth.allow-dev-login:false}") boolean allowDevLogin,
            @Value("${jjkk.auth.token-ttl-days:7}") long tokenTtlDays
    ) {
        this.userFundService = userFundService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.wechatAppId = wechatAppId;
        this.wechatAppSecret = wechatAppSecret;
        this.allowDevLogin = allowDevLogin;
        this.tokenTtl = Duration.ofDays(Math.max(1, tokenTtlDays));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(5000);
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "JJKK_2/1.0")
                .build();
    }

    /**
     * 解析微信身份、创建或更新本地用户，并把登录会话写入 Redis。
     *
     * 登录流程分三步：先用微信 code 换 openid/unionid，再把微信头像昵称等资料同步到
     * app_user，最后生成短随机 token 作为后续请求凭证。token 只在 Redis 中保存会话
     * 信息，前端拿到的只是随机字符串，不能反推出用户身份。
     */
    public AuthDtos.LoginResponse wechatLogin(AuthDtos.WechatLoginRequest request) {
        WechatIdentity identity = resolveWechatIdentity(request.code());
        UserDtos.UserResponse user = userFundService.createOrUpdateUser(new UserDtos.CreateUserRequest(
                identity.openid(),
                identity.unionid(),
                StringUtils.hasText(request.nickname()) ? request.nickname() : "\u5fae\u4fe1\u7528\u6237",
                request.avatarUrl()
        ));
        String token = newToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(tokenTtl);
        AuthSession session = new AuthSession(user.id(), identity.openid(), expiresAt);
        try {
            redisTemplate.opsForValue().set(TOKEN_PREFIX + token, objectMapper.writeValueAsString(session), tokenTtl);
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "\u767b\u5f55\u670d\u52a1\u6682\u4e0d\u53ef\u7528");
        }
        return new AuthDtos.LoginResponse(token, user.id(), user.displayUserId(), user.nickname(), expiresAt);
    }

    /**
     * 从 Authorization 请求头解析并校验 Bearer token。
     *
     * 如果 token 不存在、Redis 中没有会话、会话已过期或会话 JSON 解析失败，
     * 都按登录失效处理，让前端重新走微信授权登录。
     */
    public AuthSession requireSession(String authorization) {
        String token = bearerToken(authorization);
        if (!StringUtils.hasText(token)) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
        }
        try {
            String value = redisTemplate.opsForValue().get(TOKEN_PREFIX + token);
            if (!StringUtils.hasText(value)) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
            }
            AuthSession session = objectMapper.readValue(value, AuthSession.class);
            if (session.expiresAt() != null && session.expiresAt().isBefore(LocalDateTime.now())) {
                redisTemplate.delete(TOKEN_PREFIX + token);
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
            }
            return session;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
        }
    }

    /**
     * 校验 token 对应的用户是否就是 URL 路径中的 userId。
     *
     * 这是所有用户私有数据接口的统一入口。只要这里校验通过，后续 service 就可以
     * 安心按照 userId 查询；如果不一致，直接返回 403，防止越权访问。
     */
    public AuthSession requireUser(String authorization, Long userId) {
        AuthSession session = requireSession(authorization);
        if (userId == null || !userId.equals(session.userId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "\u65e0\u6743\u8bbf\u95ee\u8be5\u7528\u6237\u6570\u636e");
        }
        return session;
    }

    private WechatIdentity resolveWechatIdentity(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("\u5fae\u4fe1\u767b\u5f55 code \u4e0d\u80fd\u4e3a\u7a7a");
        }
        if (allowDevLogin && code.startsWith("dev:")) {
            return new WechatIdentity(code.substring(4), null);
        }
        if (!StringUtils.hasText(wechatAppId) || !StringUtils.hasText(wechatAppSecret)) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "\u5fae\u4fe1\u914d\u7f6e\u7f3a\u5c11 appId/appSecret");
        }
        try {
            String url = WECHAT_SESSION_URL
                    + "?appid=" + urlEncode(wechatAppId)
                    + "&secret=" + urlEncode(wechatAppSecret)
                    + "&js_code=" + urlEncode(code)
                    + "&grant_type=authorization_code";
            JsonNode root = objectMapper.readTree(restClient.get().uri(url).retrieve().body(String.class));
            int errcode = root.path("errcode").asInt(0);
            if (errcode != 0) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "\u5fae\u4fe1\u767b\u5f55\u5931\u8d25: " + root.path("errmsg").asText("invalid code"));
            }
            String openid = root.path("openid").asText("");
            if (!StringUtils.hasText(openid)) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
            }
            String unionid = root.path("unionid").asText(null);
            return new WechatIdentity(openid, unionid);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "\u5fae\u4fe1\u767b\u5f55\u670d\u52a1\u5f02\u5e38");
        }
    }

    private String bearerToken(String authorization) {
        if (!StringUtils.hasText(authorization)) {
            return null;
        }
        String value = authorization.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record WechatIdentity(String openid, String unionid) {
    }
}

